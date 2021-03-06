/*
 * Copyright 2014 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.co.real_logic.aeron.common.concurrent.logbuffer;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import uk.co.real_logic.agrona.MutableDirectBuffer;
import uk.co.real_logic.agrona.concurrent.UnsafeBuffer;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.agrona.BitUtil.SIZE_OF_INT;
import static uk.co.real_logic.agrona.BitUtil.align;
import static uk.co.real_logic.aeron.common.concurrent.logbuffer.FrameDescriptor.*;
import static uk.co.real_logic.aeron.common.concurrent.logbuffer.LogAppender.ActionStatus.*;
import static uk.co.real_logic.aeron.common.concurrent.logbuffer.LogBufferDescriptor.*;

public class LogAppenderTest
{
    private static final int LOG_BUFFER_CAPACITY = LogBufferDescriptor.MIN_LOG_SIZE;
    private static final int STATE_BUFFER_CAPACITY = STATE_BUFFER_LENGTH;
    private static final int MAX_FRAME_LENGTH = 1024;
    private static final MutableDirectBuffer DEFAULT_HEADER = new UnsafeBuffer(new byte[BASE_HEADER_LENGTH + SIZE_OF_INT]);

    private final UnsafeBuffer logBuffer = mock(UnsafeBuffer.class);
    private final UnsafeBuffer stateBuffer = mock(UnsafeBuffer.class);

    private LogAppender logAppender;

    @Before
    public void setUp()
    {
        when(logBuffer.capacity()).thenReturn(LOG_BUFFER_CAPACITY);
        when(stateBuffer.capacity()).thenReturn(STATE_BUFFER_CAPACITY);

        logAppender = new LogAppender(logBuffer, stateBuffer, DEFAULT_HEADER, MAX_FRAME_LENGTH);
    }

    @Test
    public void shouldReportCapacity()
    {
        assertThat(logAppender.capacity(), is(LOG_BUFFER_CAPACITY));
    }

    @Test
    public void shouldReportMaxFrameLength()
    {
        assertThat(logAppender.maxFrameLength(), is(MAX_FRAME_LENGTH));
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowExceptionOnInsufficientCapacityForLog()
    {
        when(logBuffer.capacity()).thenReturn(LogBufferDescriptor.MIN_LOG_SIZE - 1);

        logAppender = new LogAppender(logBuffer, stateBuffer, DEFAULT_HEADER, MAX_FRAME_LENGTH);
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowExceptionWhenCapacityNotMultipleOfAlignment()
    {
        final int logBufferCapacity = LogBufferDescriptor.MIN_LOG_SIZE + FRAME_ALIGNMENT + 1;
        when(logBuffer.capacity()).thenReturn(logBufferCapacity);

        logAppender = new LogAppender(logBuffer, stateBuffer, DEFAULT_HEADER, MAX_FRAME_LENGTH);
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowExceptionOnInsufficientStateBufferCapacity()
    {
        when(stateBuffer.capacity()).thenReturn(LogBufferDescriptor.STATE_BUFFER_LENGTH - 1);

        logAppender = new LogAppender(logBuffer, stateBuffer, DEFAULT_HEADER, MAX_FRAME_LENGTH);
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowExceptionOnDefaultHeaderLengthLessThanBaseHeaderLength()
    {
        final int length = BASE_HEADER_LENGTH - 1;
        logAppender = new LogAppender(logBuffer, stateBuffer, new UnsafeBuffer(new byte[length]), MAX_FRAME_LENGTH);
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowExceptionOnDefaultHeaderLengthNotOnWordSizeBoundary()
    {
        logAppender = new LogAppender(logBuffer, stateBuffer, new UnsafeBuffer(new byte[31]), MAX_FRAME_LENGTH);
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowExceptionOnMaxFrameSizeNotOnWordSizeBoundary()
    {
        logAppender = new LogAppender(logBuffer, stateBuffer, DEFAULT_HEADER, 1001);
    }

    @Test
    public void shouldReportCurrentTail()
    {
        final int tailValue = 64;

        when(stateBuffer.getIntVolatile(TAIL_COUNTER_OFFSET)).thenReturn(tailValue);

        assertThat(logAppender.tailVolatile(), is(tailValue));
    }

    @Test
    public void shouldReportCurrentTailAtCapacity()
    {
        final int tailValue = LOG_BUFFER_CAPACITY + 64;

        when(stateBuffer.getIntVolatile(TAIL_COUNTER_OFFSET)).thenReturn(tailValue);
        when(stateBuffer.getInt(TAIL_COUNTER_OFFSET)).thenReturn(tailValue);

        assertThat(logAppender.tailVolatile(), is(LOG_BUFFER_CAPACITY));
        assertThat(logAppender.tail(), is(LOG_BUFFER_CAPACITY));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowExceptionWhenMaxMessageLengthExceeded()
    {
        final int maxMessageLength = logAppender.maxMessageLength();
        final UnsafeBuffer srcBuffer = new UnsafeBuffer(new byte[1024]);

        logAppender.append(srcBuffer, 0, maxMessageLength + 1);
    }

    @Test
    public void shouldAppendFrameToEmptyLog()
    {
        final int headerLength = DEFAULT_HEADER.capacity();
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[128]);
        final int msgLength = 20;
        final int frameLength = msgLength + headerLength;
        final int alignedFrameLength = align(frameLength, FRAME_ALIGNMENT);
        final int tail = 0;

        when(stateBuffer.getAndAddInt(TAIL_COUNTER_OFFSET, alignedFrameLength)).thenReturn(0);

        assertThat(logAppender.append(buffer, 0, msgLength), is(SUCCESS));

        final InOrder inOrder = inOrder(logBuffer, stateBuffer);
        inOrder.verify(stateBuffer, times(1)).getAndAddInt(TAIL_COUNTER_OFFSET, alignedFrameLength);
        inOrder.verify(logBuffer, times(1)).putBytes(tail, DEFAULT_HEADER, 0, headerLength);
        inOrder.verify(logBuffer, times(1)).putBytes(headerLength, buffer, 0, msgLength);
        inOrder.verify(logBuffer, times(1)).putByte(flagsOffset(tail), UNFRAGMENTED);
        inOrder.verify(logBuffer, times(1)).putInt(termOffsetOffset(tail), tail, LITTLE_ENDIAN);
        inOrder.verify(logBuffer, times(1)).putIntOrdered(lengthOffset(tail), frameLength);
    }

    @Test
    public void shouldAppendFrameTwiceToLog()
    {
        final int headerLength = DEFAULT_HEADER.capacity();
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[128]);
        final int msgLength = 20;
        final int frameLength = msgLength + headerLength;
        final int alignedFrameLength = align(frameLength, FRAME_ALIGNMENT);
        int tail = 0;

        when(stateBuffer.getAndAddInt(TAIL_COUNTER_OFFSET, alignedFrameLength))
            .thenReturn(0)
            .thenReturn(alignedFrameLength);

        assertThat(logAppender.append(buffer, 0, msgLength), is(SUCCESS));
        assertThat(logAppender.append(buffer, 0, msgLength), is(SUCCESS));

        final InOrder inOrder = inOrder(logBuffer, stateBuffer);
        inOrder.verify(stateBuffer, times(1)).getAndAddInt(TAIL_COUNTER_OFFSET, alignedFrameLength);
        inOrder.verify(logBuffer, times(1)).putBytes(tail, DEFAULT_HEADER, 0, headerLength);
        inOrder.verify(logBuffer, times(1)).putBytes(headerLength, buffer, 0, msgLength);
        inOrder.verify(logBuffer, times(1)).putByte(flagsOffset(tail), UNFRAGMENTED);
        inOrder.verify(logBuffer, times(1)).putInt(termOffsetOffset(tail), tail, LITTLE_ENDIAN);
        inOrder.verify(logBuffer, times(1)).putIntOrdered(lengthOffset(tail), frameLength);

        tail = alignedFrameLength;
        inOrder.verify(stateBuffer, times(1)).getAndAddInt(TAIL_COUNTER_OFFSET, alignedFrameLength);
        inOrder.verify(logBuffer, times(1)).putBytes(tail, DEFAULT_HEADER, 0, headerLength);
        inOrder.verify(logBuffer, times(1)).putBytes(tail + headerLength, buffer, 0, msgLength);
        inOrder.verify(logBuffer, times(1)).putByte(flagsOffset(tail), UNFRAGMENTED);
        inOrder.verify(logBuffer, times(1)).putInt(termOffsetOffset(tail), tail, LITTLE_ENDIAN);
        inOrder.verify(logBuffer, times(1)).putIntOrdered(lengthOffset(tail), frameLength);
    }

    @Test
    public void shouldTripWhenAppendingToLogAtCapacity()
    {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[128]);
        final int headerLength = DEFAULT_HEADER.capacity();
        final int msgLength = 20;
        final int frameLength = msgLength + headerLength;
        final int alignedFrameLength = align(frameLength, FRAME_ALIGNMENT);

        when(stateBuffer.getAndAddInt(TAIL_COUNTER_OFFSET, alignedFrameLength))
            .thenReturn(logAppender.capacity());

        assertThat(logAppender.append(buffer, 0, msgLength), is(TRIPPED));

        verify(stateBuffer, times(1)).getAndAddInt(TAIL_COUNTER_OFFSET, alignedFrameLength);
        verify(logBuffer, atLeastOnce()).capacity();
        verifyNoMoreInteractions(logBuffer);
    }

    @Test
    public void shouldFailWhenTheLogIsAlreadyTripped()
    {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[128]);
        final int headerLength = DEFAULT_HEADER.capacity();
        final int msgLength = 20;
        final int frameLength = msgLength + headerLength;
        final int alignedFrameLength = align(frameLength, FRAME_ALIGNMENT);

        when(stateBuffer.getAndAddInt(TAIL_COUNTER_OFFSET, alignedFrameLength))
            .thenReturn(logAppender.capacity())
            .thenReturn(logAppender.capacity() + alignedFrameLength);

        assertThat(logAppender.append(buffer, 0, msgLength), is(TRIPPED));

        assertThat(logAppender.append(buffer, 0, msgLength), is(FAILURE));

        verify(logBuffer, never()).putBytes(anyInt(), eq(buffer), eq(0), eq(msgLength));
    }

    @Test
    public void shouldPadLogAndTripWhenAppendingWithInsufficientRemainingCapacity()
    {
        final int msgLength = 120;
        final int headerLength = DEFAULT_HEADER.capacity();
        final int requiredFrameSize = align(headerLength + msgLength, FRAME_ALIGNMENT);
        final int tailValue = logAppender.capacity() - align(msgLength, FRAME_ALIGNMENT);
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[128]);

        when(stateBuffer.getAndAddInt(TAIL_COUNTER_OFFSET, requiredFrameSize))
            .thenReturn(tailValue);

        assertThat(logAppender.append(buffer, 0, msgLength), is(TRIPPED));

        final InOrder inOrder = inOrder(logBuffer, stateBuffer);
        inOrder.verify(stateBuffer, times(1)).getAndAddInt(TAIL_COUNTER_OFFSET, requiredFrameSize);
        inOrder.verify(logBuffer, times(1)).putBytes(tailValue, DEFAULT_HEADER, 0, headerLength);
        inOrder.verify(logBuffer, times(1)).putShort(typeOffset(tailValue), (short)PADDING_FRAME_TYPE, LITTLE_ENDIAN);
        inOrder.verify(logBuffer, times(1)).putByte(flagsOffset(tailValue), UNFRAGMENTED);
        inOrder.verify(logBuffer, times(1)).putInt(termOffsetOffset(tailValue), tailValue, LITTLE_ENDIAN);
        inOrder.verify(logBuffer, times(1)).putIntOrdered(lengthOffset(tailValue), LOG_BUFFER_CAPACITY - tailValue);
    }

    @Test
    public void shouldPadLogAndTripWhenAppendingWithInsufficientRemainingCapacityIncludingHeader()
    {
        final int headerLength = DEFAULT_HEADER.capacity();
        final int msgLength = 120;
        final int requiredFrameSize = align(headerLength + msgLength, FRAME_ALIGNMENT);
        final int tailValue = logAppender.capacity() - (requiredFrameSize + (headerLength - FRAME_ALIGNMENT));
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[128]);

        when(stateBuffer.getAndAddInt(TAIL_COUNTER_OFFSET, requiredFrameSize))
            .thenReturn(tailValue);

        assertThat(logAppender.append(buffer, 0, msgLength), is(TRIPPED));

        final InOrder inOrder = inOrder(logBuffer, stateBuffer);
        inOrder.verify(stateBuffer, times(1)).getAndAddInt(TAIL_COUNTER_OFFSET, requiredFrameSize);
        inOrder.verify(logBuffer, times(1)).putBytes(tailValue, DEFAULT_HEADER, 0, headerLength);
        inOrder.verify(logBuffer, times(1)).putShort(typeOffset(tailValue), (short)PADDING_FRAME_TYPE, LITTLE_ENDIAN);
        inOrder.verify(logBuffer, times(1)).putByte(flagsOffset(tailValue), UNFRAGMENTED);
        inOrder.verify(logBuffer, times(1)).putInt(termOffsetOffset(tailValue), tailValue, LITTLE_ENDIAN);
        inOrder.verify(logBuffer, times(1)).putIntOrdered(lengthOffset(tailValue), LOG_BUFFER_CAPACITY - tailValue);
    }

    @Test
    public void shouldFragmentMessageOverTwoFrames()
    {
        final int msgLength = logAppender.maxPayloadLength() + 1;
        final int headerLength = DEFAULT_HEADER.capacity();
        final int frameLength = headerLength + 1;
        final int requiredCapacity = align(headerLength + 1, FRAME_ALIGNMENT) + logAppender.maxFrameLength();
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[msgLength]);

        when(stateBuffer.getAndAddInt(TAIL_COUNTER_OFFSET, requiredCapacity))
            .thenReturn(0);

        assertThat(logAppender.append(buffer, 0, msgLength), is(SUCCESS));

        int tail  = 0;
        final InOrder inOrder = inOrder(logBuffer, stateBuffer);
        inOrder.verify(stateBuffer, times(1)).getAndAddInt(TAIL_COUNTER_OFFSET, requiredCapacity);

        inOrder.verify(logBuffer, times(1)).putBytes(tail, DEFAULT_HEADER, 0, headerLength);
        inOrder.verify(logBuffer, times(1)).putBytes(tail + headerLength, buffer, 0, logAppender.maxPayloadLength());
        inOrder.verify(logBuffer, times(1)).putByte(flagsOffset(tail), BEGIN_FRAG);
        inOrder.verify(logBuffer, times(1)).putInt(termOffsetOffset(tail), tail, LITTLE_ENDIAN);
        inOrder.verify(logBuffer, times(1)).putIntOrdered(lengthOffset(tail), logAppender.maxFrameLength());

        tail = logAppender.maxFrameLength();
        inOrder.verify(logBuffer, times(1)).putBytes(tail, DEFAULT_HEADER, 0, headerLength);
        inOrder.verify(logBuffer, times(1)).putBytes(tail + headerLength, buffer, logAppender.maxPayloadLength(), 1);
        inOrder.verify(logBuffer, times(1)).putByte(flagsOffset(tail), END_FRAG);
        inOrder.verify(logBuffer, times(1)).putInt(termOffsetOffset(tail), tail, LITTLE_ENDIAN);
        inOrder.verify(logBuffer, times(1)).putIntOrdered(lengthOffset(tail), frameLength);
    }

    @Test
    public void shouldClaimRegionForZeroCopyEncoding()
    {
        final int headerLength = DEFAULT_HEADER.capacity();
        final int msgLength = 20;
        final int frameLength = msgLength + headerLength;
        final int alignedFrameLength = align(frameLength, FRAME_ALIGNMENT);
        final int tail = 0;
        final BufferClaim bufferClaim = new BufferClaim();

        when(stateBuffer.getAndAddInt(TAIL_COUNTER_OFFSET, alignedFrameLength)).thenReturn(0);

        assertThat(logAppender.claim(msgLength, bufferClaim), is(SUCCESS));

        assertThat(bufferClaim.buffer(), is(logBuffer));
        assertThat(bufferClaim.offset(), is(tail + headerLength));
        assertThat(bufferClaim.length(), is(msgLength));

        // Map flyweight or encode to buffer directly then call commit() when done
        bufferClaim.commit();

        final InOrder inOrder = inOrder(logBuffer, stateBuffer);
        inOrder.verify(stateBuffer, times(1)).getAndAddInt(TAIL_COUNTER_OFFSET, alignedFrameLength);
        inOrder.verify(logBuffer, times(1)).putBytes(tail, DEFAULT_HEADER, 0, headerLength);
        inOrder.verify(logBuffer, times(1)).putByte(flagsOffset(tail), UNFRAGMENTED);
        inOrder.verify(logBuffer, times(1)).putInt(termOffsetOffset(tail), tail, LITTLE_ENDIAN);
        inOrder.verify(logBuffer, times(1)).putIntOrdered(lengthOffset(tail), frameLength);
    }
}
