/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.io.network.partition.consumer;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.core.memory.MemorySegment;
import org.apache.flink.runtime.event.TaskEvent;
import org.apache.flink.runtime.io.network.ConnectionID;
import org.apache.flink.runtime.io.network.ConnectionManager;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.buffer.BufferListener;
import org.apache.flink.runtime.io.network.buffer.BufferProvider;
import org.apache.flink.runtime.io.network.buffer.BufferRecycler;
import org.apache.flink.runtime.io.network.netty.PartitionRequestClient;
import org.apache.flink.runtime.io.network.partition.PartitionNotFoundException;
import org.apache.flink.runtime.io.network.partition.ResultPartitionID;
import org.apache.flink.runtime.metrics.groups.TaskIOMetricGroup;
import org.apache.flink.util.ExceptionUtils;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * An input channel, which requests a remote partition queue.
 */
public class RemoteInputChannel extends InputChannel implements BufferRecycler, BufferListener {

	/** ID to distinguish this channel from other channels sharing the same TCP connection. */
	private final InputChannelID id = new InputChannelID();

	/** The connection to use to request the remote partition. */
	private final ConnectionID connectionId;

	/** The connection manager to use connect to the remote partition provider. */
	private final ConnectionManager connectionManager;

	/**
	 * The received buffers. Received buffers are enqueued by the network I/O thread and the queue
	 * is consumed by the receiving task thread.
	 */
	private final ArrayDeque<Buffer> receivedBuffers = new ArrayDeque<>();

	/**
	 * Flag indicating whether this channel has been released. Either called by the receiving task
	 * thread or the task manager actor.
	 */
	private final AtomicBoolean isReleased = new AtomicBoolean();

	/** Client to establish a (possibly shared) TCP connection and request the partition. */
	private volatile PartitionRequestClient partitionRequestClient;

	/**
	 * The next expected sequence number for the next buffer. This is modified by the network
	 * I/O thread only.
	 */
	private int expectedSequenceNumber = 0;

	/** The initial number of exclusive buffers assigned to this channel. */
	private int initialCredit;

	/** The available buffer queue wraps both exclusive and requested floating buffers. */
	private final AvailableBufferQueue bufferQueue = new AvailableBufferQueue();

	/** The number of available buffers that have not been announced to the producer yet. */
	private final AtomicInteger unannouncedCredit = new AtomicInteger(0);

	/** The number of required buffers that equals to sender's backlog plus initial credit. */
	@GuardedBy("bufferQueue")
	private int numRequiredBuffers;

	/** The tag indicates whether this channel is waiting for additional floating buffers from the buffer pool. */
	@GuardedBy("bufferQueue")
	private boolean isWaitingForFloatingBuffers;

	public RemoteInputChannel(
		SingleInputGate inputGate,
		int channelIndex,
		ResultPartitionID partitionId,
		ConnectionID connectionId,
		ConnectionManager connectionManager,
		TaskIOMetricGroup metrics) {

		this(inputGate, channelIndex, partitionId, connectionId, connectionManager, 0, 0, metrics);
	}

	public RemoteInputChannel(
		SingleInputGate inputGate,
		int channelIndex,
		ResultPartitionID partitionId,
		ConnectionID connectionId,
		ConnectionManager connectionManager,
		int initialBackOff,
		int maxBackoff,
		TaskIOMetricGroup metrics) {

		super(inputGate, channelIndex, partitionId, initialBackOff, maxBackoff, metrics.getNumBytesInRemoteCounter());

		this.connectionId = checkNotNull(connectionId);
		this.connectionManager = checkNotNull(connectionManager);
	}

	/**
	 * Assigns exclusive buffers to this input channel, and this method should be called only once
	 * after this input channel is created.
	 */
	void assignExclusiveSegments(List<MemorySegment> segments) {
		checkState(this.initialCredit == 0, "Bug in input channel setup logic: exclusive buffers have " +
			"already been set for this input channel.");

		checkNotNull(segments);
		checkArgument(segments.size() > 0, "The number of exclusive buffers per channel should be larger than 0.");

		this.initialCredit = segments.size();
		this.numRequiredBuffers = segments.size();

		synchronized(bufferQueue) {
			for (MemorySegment segment : segments) {
				bufferQueue.addExclusiveBuffer(new Buffer(segment, this), numRequiredBuffers);
			}
		}
	}

	// ------------------------------------------------------------------------
	// Consume
	// ------------------------------------------------------------------------

	/**
	 * Requests a remote subpartition.
	 */
	@Override
	void requestSubpartition(int subpartitionIndex) throws IOException, InterruptedException {
		if (partitionRequestClient == null) {
			// Create a client and request the partition
			partitionRequestClient = connectionManager
				.createPartitionRequestClient(connectionId);

			partitionRequestClient.requestSubpartition(partitionId, subpartitionIndex, this, 0);
		}
	}

	/**
	 * Retriggers a remote subpartition request.
	 */
	void retriggerSubpartitionRequest(int subpartitionIndex) throws IOException, InterruptedException {
		checkState(partitionRequestClient != null, "Missing initial subpartition request.");

		if (increaseBackoff()) {
			partitionRequestClient.requestSubpartition(
				partitionId, subpartitionIndex, this, getCurrentBackoff());
		} else {
			failPartitionRequest();
		}
	}

	@Override
	BufferAndAvailability getNextBuffer() throws IOException {
		checkState(!isReleased.get(), "Queried for a buffer after channel has been closed.");
		checkState(partitionRequestClient != null, "Queried for a buffer before requesting a queue.");

		checkError();

		final Buffer next;
		final int remaining;

		synchronized (receivedBuffers) {
			next = receivedBuffers.poll();
			remaining = receivedBuffers.size();
		}

		numBytesIn.inc(next.getSizeUnsafe());
		return new BufferAndAvailability(next, remaining > 0);
	}

	// ------------------------------------------------------------------------
	// Task events
	// ------------------------------------------------------------------------

	@Override
	void sendTaskEvent(TaskEvent event) throws IOException {
		checkState(!isReleased.get(), "Tried to send task event to producer after channel has been released.");
		checkState(partitionRequestClient != null, "Tried to send task event to producer before requesting a queue.");

		checkError();

		partitionRequestClient.sendTaskEvent(partitionId, event, this);
	}

	// ------------------------------------------------------------------------
	// Life cycle
	// ------------------------------------------------------------------------

	@Override
	public boolean isReleased() {
		return isReleased.get();
	}

	@Override
	void notifySubpartitionConsumed() {
		// Nothing to do
	}

	/**
	 * Releases all exclusive and floating buffers, closes the partition request client.
	 */
	@Override
	void releaseAllResources() throws IOException {
		if (isReleased.compareAndSet(false, true)) {

			// Gather all exclusive buffers and recycle them to global pool in batch, because
			// we do not want to trigger redistribution of buffers after each recycle.
			final List<MemorySegment> exclusiveRecyclingSegments = new ArrayList<>();

			synchronized (receivedBuffers) {
				Buffer buffer;
				while ((buffer = receivedBuffers.poll()) != null) {
					if (buffer.getRecycler() == this) {
						exclusiveRecyclingSegments.add(buffer.getMemorySegment());
					} else {
						buffer.recycle();
					}
				}
			}
			synchronized (bufferQueue) {
				bufferQueue.releaseAll(exclusiveRecyclingSegments);
			}

			if (exclusiveRecyclingSegments.size() > 0) {
				inputGate.returnExclusiveSegments(exclusiveRecyclingSegments);
			}

			// The released flag has to be set before closing the connection to ensure that
			// buffers received concurrently with closing are properly recycled.
			if (partitionRequestClient != null) {
				partitionRequestClient.close(this);
			} else {
				connectionManager.closeOpenChannelConnections(connectionId);
			}
		}
	}

	private void failPartitionRequest() {
		setError(new PartitionNotFoundException(partitionId));
	}

	@Override
	public String toString() {
		return "RemoteInputChannel [" + partitionId + " at " + connectionId + "]";
	}

	// ------------------------------------------------------------------------
	// Credit-based
	// ------------------------------------------------------------------------

	/**
	 * Enqueue this input channel in the pipeline for sending unannounced credits to producer.
	 */
	void notifyCreditAvailable() {
		//TODO in next PR
	}

	/**
	 * Exclusive buffer is recycled to this input channel directly and it may trigger return extra
	 * floating buffer and notify increased credit to the producer.
	 *
	 * @param segment The exclusive segment of this channel.
	 */
	@Override
	public void recycle(MemorySegment segment) {
		int numAddedBuffers;

		synchronized (bufferQueue) {
			// Important: check the isReleased state inside synchronized block, so there is no
			// race condition when recycle and releaseAllResources running in parallel.
			if (isReleased.get()) {
				try {
					inputGate.returnExclusiveSegments(Collections.singletonList(segment));
					return;
				} catch (Throwable t) {
					ExceptionUtils.rethrow(t);
				}
			}
			numAddedBuffers = bufferQueue.addExclusiveBuffer(new Buffer(segment, this), numRequiredBuffers);
		}

		if (numAddedBuffers > 0 && unannouncedCredit.getAndAdd(numAddedBuffers) == 0) {
			notifyCreditAvailable();
		}
	}

	public int getNumberOfAvailableBuffers() {
		synchronized (bufferQueue) {
			return bufferQueue.getAvailableBufferSize();
		}
	}

	@VisibleForTesting
	public int getNumberOfRequiredBuffers() {
		return numRequiredBuffers;
	}

	/**
	 * The Buffer pool notifies this channel of an available floating buffer. If the channel is released or
	 * currently does not need extra buffers, the buffer should be recycled to the buffer pool. Otherwise,
	 * the buffer will be added into the <tt>bufferQueue</tt> and the unannounced credit is increased
	 * by one.
	 *
	 * @param buffer Buffer that becomes available in buffer pool.
	 * @return True when this channel is waiting for more floating buffers, otherwise false.
	 */
	@Override
	public boolean notifyBufferAvailable(Buffer buffer) {
		// Check the isReleased state outside synchronized block first to avoid
		// deadlock with releaseAllResources running in parallel.
		if (isReleased.get()) {
			buffer.recycle();
			return false;
		}

		boolean needMoreBuffers = false;
		synchronized (bufferQueue) {
			checkState(isWaitingForFloatingBuffers, "This channel should be waiting for floating buffers.");

			// Important: double check the isReleased state inside synchronized block, so there is no
			// race condition when notifyBufferAvailable and releaseAllResources running in parallel.
			if (isReleased.get() || bufferQueue.getAvailableBufferSize() >= numRequiredBuffers) {
				buffer.recycle();
				return false;
			}

			bufferQueue.addFloatingBuffer(buffer);

			if (bufferQueue.getAvailableBufferSize() == numRequiredBuffers) {
				isWaitingForFloatingBuffers = false;
			} else {
				needMoreBuffers =  true;
			}
		}

		if (unannouncedCredit.getAndAdd(1) == 0) {
			notifyCreditAvailable();
		}

		return needMoreBuffers;
	}

	@Override
	public void notifyBufferDestroyed() {
		// Nothing to do actually.
	}

	// ------------------------------------------------------------------------
	// Network I/O notifications (called by network I/O thread)
	// ------------------------------------------------------------------------

	public int getNumberOfQueuedBuffers() {
		synchronized (receivedBuffers) {
			return receivedBuffers.size();
		}
	}

	public int unsynchronizedGetNumberOfQueuedBuffers() {
		return Math.max(0, receivedBuffers.size());
	}

	public InputChannelID getInputChannelId() {
		return id;
	}

	public int getInitialCredit() {
		return initialCredit;
	}

	public BufferProvider getBufferProvider() throws IOException {
		if (isReleased.get()) {
			return null;
		}

		return inputGate.getBufferProvider();
	}

	/**
	 * Requests buffer from input channel directly for receiving network data.
	 * It should always return an available buffer in credit-based mode unless
	 * the channel has been released.
	 *
	 * @return The available buffer.
	 */
	@Nullable
	public Buffer requestBuffer() {
		synchronized (bufferQueue) {
			return bufferQueue.takeBuffer();
		}
	}

	/**
	 * Receives the backlog from the producer's buffer response. If the number of available
	 * buffers is less than backlog + initialCredit, it will request floating buffers from the buffer
	 * pool, and then notify unannounced credits to the producer.
	 *
	 * @param backlog The number of unsent buffers in the producer's sub partition.
	 */
	@VisibleForTesting
	void onSenderBacklog(int backlog) throws IOException {
		int numRequestedBuffers = 0;

		synchronized (bufferQueue) {
			// Important: check the isReleased state inside synchronized block, so there is no
			// race condition when onSenderBacklog and releaseAllResources running in parallel.
			if (isReleased.get()) {
				return;
			}

			numRequiredBuffers = backlog + initialCredit;
			while (bufferQueue.getAvailableBufferSize() < numRequiredBuffers && !isWaitingForFloatingBuffers) {
				Buffer buffer = inputGate.getBufferPool().requestBuffer();
				if (buffer != null) {
					bufferQueue.addFloatingBuffer(buffer);
					numRequestedBuffers++;
				} else if (inputGate.getBufferProvider().addBufferListener(this)) {
					// If the channel has not got enough buffers, register it as listener to wait for more floating buffers.
					isWaitingForFloatingBuffers = true;
					break;
				}
			}
		}

		if (numRequestedBuffers > 0 && unannouncedCredit.getAndAdd(numRequestedBuffers) == 0) {
			notifyCreditAvailable();
		}
	}

	public void onBuffer(Buffer buffer, int sequenceNumber, int backlog) throws IOException {
		boolean success = false;

		try {
			synchronized (receivedBuffers) {
				if (!isReleased.get()) {
					if (expectedSequenceNumber == sequenceNumber) {
						int available = receivedBuffers.size();

						receivedBuffers.add(buffer);
						expectedSequenceNumber++;

						if (available == 0) {
							notifyChannelNonEmpty();
						}

						success = true;
					} else {
						onError(new BufferReorderingException(expectedSequenceNumber, sequenceNumber));
					}
				}
			}

			if (success && backlog >= 0) {
				onSenderBacklog(backlog);
			}
		} finally {
			if (!success) {
				buffer.recycle();
			}
		}
	}

	public void onEmptyBuffer(int sequenceNumber, int backlog) throws IOException {
		boolean success = false;

		synchronized (receivedBuffers) {
			if (!isReleased.get()) {
				if (expectedSequenceNumber == sequenceNumber) {
					expectedSequenceNumber++;
					success = true;
				} else {
					onError(new BufferReorderingException(expectedSequenceNumber, sequenceNumber));
				}
			}
		}

		if (success && backlog >= 0) {
			onSenderBacklog(backlog);
		}
	}

	public void onFailedPartitionRequest() {
		inputGate.triggerPartitionStateCheck(partitionId);
	}

	public void onError(Throwable cause) {
		setError(cause);
	}

	private static class BufferReorderingException extends IOException {

		private static final long serialVersionUID = -888282210356266816L;

		private final int expectedSequenceNumber;

		private final int actualSequenceNumber;

		BufferReorderingException(int expectedSequenceNumber, int actualSequenceNumber) {
			this.expectedSequenceNumber = expectedSequenceNumber;
			this.actualSequenceNumber = actualSequenceNumber;
		}

		@Override
		public String getMessage() {
			return String.format("Buffer re-ordering: expected buffer with sequence number %d, but received %d.",
				expectedSequenceNumber, actualSequenceNumber);
		}
	}

	/**
	 * Manages the exclusive and floating buffers of this channel, and handles the
	 * internal buffer related logic.
	 */
	private static class AvailableBufferQueue {

		/** The current available floating buffers from the fixed buffer pool. */
		private final ArrayDeque<Buffer> floatingBuffers;

		/** The current available exclusive buffers from the global buffer pool. */
		private final ArrayDeque<Buffer> exclusiveBuffers;

		AvailableBufferQueue() {
			this.exclusiveBuffers = new ArrayDeque<>();
			this.floatingBuffers = new ArrayDeque<>();
		}

		/**
		 * Adds an exclusive buffer (back) into the queue and recycles one floating buffer if the
		 * number of available buffers in queue is more than the required amount.
		 *
		 * @param buffer The exclusive buffer to add
		 * @param numRequiredBuffers The number of required buffers
		 *
		 * @return How many buffers were added to the queue
		 */
		int addExclusiveBuffer(Buffer buffer, int numRequiredBuffers) {
			exclusiveBuffers.add(buffer);
			if (getAvailableBufferSize() > numRequiredBuffers) {
				Buffer floatingBuffer = floatingBuffers.poll();
				floatingBuffer.recycle();
				return 0;
			} else {
				return 1;
			}
		}

		void addFloatingBuffer(Buffer buffer) {
			floatingBuffers.add(buffer);
		}

		/**
		 * Takes the floating buffer first in order to make full use of floating
		 * buffers reasonably.
		 *
		 * @return An available floating or exclusive buffer, may be null
		 * if the channel is released.
		 */
		@Nullable
		Buffer takeBuffer() {
			if (floatingBuffers.size() > 0) {
				return floatingBuffers.poll();
			} else {
				return exclusiveBuffers.poll();
			}
		}

		/**
		 * The floating buffer is recycled to local buffer pool directly, and the
		 * exclusive buffer will be gathered to return to global buffer pool later.
		 *
		 * @param exclusiveSegments The list that we will add exclusive segments into.
		 */
		void releaseAll(List<MemorySegment> exclusiveSegments) {
			Buffer buffer;
			while ((buffer = floatingBuffers.poll()) != null) {
				buffer.recycle();
			}
			while ((buffer = exclusiveBuffers.poll()) != null) {
				exclusiveSegments.add(buffer.getMemorySegment());
			}
		}

		int getAvailableBufferSize() {
			return floatingBuffers.size() + exclusiveBuffers.size();
		}
	}
}
