/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.buffer;

import io.netty.util.ResourceLeak;
import io.netty.util.internal.EmptyArrays;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

/**
 * A virtual buffer which shows multiple buffers as a single merged buffer.  It is recommended to use
 * {@link ByteBufAllocator#compositeBuffer()} or {@link Unpooled#wrappedBuffer(ByteBuf...)} instead of calling the
 * constructor explicitly.
 */
public class CompositeByteBuf extends AbstractReferenceCountedByteBuf {

    private final ResourceLeak leak;
    private final ByteBufAllocator alloc;
    private final boolean direct;
    private final List<Component> components = new ArrayList<Component>();
    private final int maxNumComponents;
    private String url = "UNKNOWN";
    private static final ByteBuffer FULL_BYTEBUFFER = (ByteBuffer) ByteBuffer.allocate(1).position(1);

    private boolean freed;

    public CompositeByteBuf(ByteBufAllocator alloc, boolean direct, int maxNumComponents) {
      this(alloc, direct, maxNumComponents, "UNKNOWN");
    }

    public CompositeByteBuf(ByteBufAllocator alloc, boolean direct, int maxNumComponents, String url) {
        super(Integer.MAX_VALUE);
        if (alloc == null) {
            throw new NullPointerException("alloc");
        }
        this.alloc = alloc;
        this.direct = direct;
        this.maxNumComponents = maxNumComponents;
        this.url = url
        leak = leakDetector.open(this);
    }

    public String getUrl() {
        return url;
    }

    public CompositeByteBuf(ByteBufAllocator alloc, boolean direct, int maxNumComponents, ByteBuf... buffers) {
        super(Integer.MAX_VALUE);
        if (alloc == null) {
            throw new NullPointerException("alloc");
        }
        if (maxNumComponents < 2) {
            throw new IllegalArgumentException(
                    "maxNumComponents: " + maxNumComponents + " (expected: >= 2)");
        }

        this.alloc = alloc;
        this.direct = direct;
        this.maxNumComponents = maxNumComponents;

        addComponents0(0, buffers);
        consolidateIfNeeded();
        setIndex(0, capacity());
        leak = leakDetector.open(this);
    }

    public CompositeByteBuf(
            ByteBufAllocator alloc, boolean direct, int maxNumComponents, Iterable<ByteBuf> buffers) {
        super(Integer.MAX_VALUE);
        if (alloc == null) {
            throw new NullPointerException("alloc");
        }
        if (maxNumComponents < 2) {
            throw new IllegalArgumentException(
                    "maxNumComponents: " + maxNumComponents + " (expected: >= 2)");
        }

        this.alloc = alloc;
        this.direct = direct;
        this.maxNumComponents = maxNumComponents;
        addComponents0(0, buffers);
        consolidateIfNeeded();
        setIndex(0, capacity());
        leak = leakDetector.open(this);
    }

    /**
     * Add the given {@link ByteBuf}.
     *
     * Be aware that this method does not increase the {@code writerIndex} of the {@link CompositeByteBuf}.
     * If you need to have it increased you need to handle it by your own.
     *
     * @param buffer the {@link ByteBuf} to add
     */
    public CompositeByteBuf addComponent(ByteBuf buffer) {
        addComponent0(components.size(), buffer);
        consolidateIfNeeded();
        return this;
    }

    /**
     * Add the given {@link ByteBuf}s.
     *
     * Be aware that this method does not increase the {@code writerIndex} of the {@link CompositeByteBuf}.
     * If you need to have it increased you need to handle it by your own.
     *
     * @param buffers the {@link ByteBuf}s to add
     */
    public CompositeByteBuf addComponents(ByteBuf... buffers) {
        addComponents0(components.size(), buffers);
        consolidateIfNeeded();
        return this;
    }

    /**
     * Add the given {@link ByteBuf}s.
     *
     * Be aware that this method does not increase the {@code writerIndex} of the {@link CompositeByteBuf}.
     * If you need to have it increased you need to handle it by your own.
     *
     * @param buffers the {@link ByteBuf}s to add
     */
    public CompositeByteBuf addComponents(Iterable<ByteBuf> buffers) {
        addComponents0(components.size(), buffers);
        consolidateIfNeeded();
        return this;
    }

    /**
     * Add the given {@link ByteBuf} on the specific index.
     *
     * Be aware that this method does not increase the {@code writerIndex} of the {@link CompositeByteBuf}.
     * If you need to have it increased you need to handle it by your own.
     *
     * @param cIndex the index on which the {@link ByteBuf} will be added
     * @param buffer the {@link ByteBuf} to add
     */
    public CompositeByteBuf addComponent(int cIndex, ByteBuf buffer) {
        addComponent0(cIndex, buffer);
        consolidateIfNeeded();
        return this;
    }

    private int addComponent0(int cIndex, ByteBuf buffer) {
        checkComponentIndex(cIndex);

        if (buffer == null) {
            throw new NullPointerException("buffer");
        }

        int readableBytes = buffer.readableBytes();
        if (readableBytes == 0) {
            return cIndex;
        }

        // No need to consolidate - just add a component to the list.
        Component c = new Component(buffer.order(ByteOrder.BIG_ENDIAN).slice());
        if (cIndex == components.size()) {
            components.add(c);
            if (cIndex == 0) {
                c.endOffset = readableBytes;
            } else {
                Component prev = components.get(cIndex - 1);
                c.offset = prev.endOffset;
                c.endOffset = c.offset + readableBytes;
            }
        } else {
            components.add(cIndex, c);
            updateComponentOffsets(cIndex);
        }
        return cIndex;
    }

    /**
     * Add the given {@link ByteBuf}s on the specific index
     *
     * Be aware that this method does not increase the {@code writerIndex} of the {@link CompositeByteBuf}.
     * If you need to have it increased you need to handle it by your own.
     *
     * @param cIndex the index on which the {@link ByteBuf} will be added.
     * @param buffers the {@link ByteBuf}s to add
     */
    public CompositeByteBuf addComponents(int cIndex, ByteBuf... buffers) {
        addComponents0(cIndex, buffers);
        consolidateIfNeeded();
        return this;
    }

    private int addComponents0(int cIndex, ByteBuf... buffers) {
        checkComponentIndex(cIndex);

        if (buffers == null) {
            throw new NullPointerException("buffers");
        }

        int readableBytes = 0;
        for (ByteBuf b: buffers) {
            if (b == null) {
                break;
            }
            readableBytes += b.readableBytes();
        }

        if (readableBytes == 0) {
            return cIndex;
        }

        // No need for consolidation
        for (ByteBuf b: buffers) {
            if (b == null) {
                break;
            }
            if (b.isReadable()) {
                cIndex = addComponent0(cIndex, b) + 1;
                int size = components.size();
                if (cIndex > size) {
                    cIndex = size;
                }
            } else {
                b.release();
            }
        }
        return cIndex;
    }

    /**
     * Add the given {@link ByteBuf}s on the specific index
     *
     * Be aware that this method does not increase the {@code writerIndex} of the {@link CompositeByteBuf}.
     * If you need to have it increased you need to handle it by your own.
     *
     * @param cIndex the index on which the {@link ByteBuf} will be added.
     * @param buffers the {@link ByteBuf}s to add
     */
    public CompositeByteBuf addComponents(int cIndex, Iterable<ByteBuf> buffers) {
        addComponents0(cIndex, buffers);
        consolidateIfNeeded();
        return this;
    }

    private int addComponents0(int cIndex, Iterable<ByteBuf> buffers) {
        if (buffers == null) {
            throw new NullPointerException("buffers");
        }

        if (buffers instanceof ByteBuf) {
            // If buffers also implements ByteBuf (e.g. CompositeByteBuf), it has to go to addComponent(ByteBuf).
            return addComponent0(cIndex, (ByteBuf) buffers);
        }

        if (!(buffers instanceof Collection)) {
            List<ByteBuf> list = new ArrayList<ByteBuf>();
            for (ByteBuf b: buffers) {
                list.add(b);
            }
            buffers = list;
        }

        Collection<ByteBuf> col = (Collection<ByteBuf>) buffers;
        return addComponents0(cIndex, col.toArray(new ByteBuf[col.size()]));
    }

    /**
     * This should only be called as last operation from a method as this may adjust the underlying
     * array of components and so affect the index etc.
     */
    private void consolidateIfNeeded() {
        // Consolidate if the number of components will exceed the allowed maximum by the current
        // operation.
        final int numComponents = components.size();
        if (numComponents > maxNumComponents) {
            final int capacity = components.get(numComponents - 1).endOffset;

            ByteBuf consolidated = allocBuffer(capacity);

            // We're not using foreach to avoid creating an iterator.
            // noinspection ForLoopReplaceableByForEach
            for (int i = 0; i < numComponents; i ++) {
                Component c = components.get(i);
                ByteBuf b = c.buf;
                consolidated.writeBytes(b);
                c.freeIfNecessary();
            }
            Component c = new Component(consolidated);
            c.endOffset = c.length;
            components.clear();
            components.add(c);
        }
    }

    private void checkComponentIndex(int cIndex) {
        assert !freed;
        if (cIndex < 0 || cIndex > components.size()) {
            throw new IndexOutOfBoundsException(String.format(
                    "cIndex: %d (expected: >= 0 && <= numComponents(%d))",
                    cIndex, components.size()));
        }
    }

    private void checkComponentIndex(int cIndex, int numComponents) {
        assert !freed;
        if (cIndex < 0 || cIndex + numComponents > components.size()) {
            throw new IndexOutOfBoundsException(String.format(
                    "cIndex: %d, numComponents: %d " +
                    "(expected: cIndex >= 0 && cIndex + numComponents <= totalNumComponents(%d))",
                    cIndex, numComponents, components.size()));
        }
    }

    private void updateComponentOffsets(int cIndex) {
        int size = components.size();
        if (size <= cIndex) {
            return;
        }

        Component c = components.get(cIndex);
        if (cIndex == 0) {
            c.offset = 0;
            c.endOffset = c.length;
            cIndex ++;
        }

        for (int i = cIndex; i < size; i ++) {
            Component prev = components.get(i - 1);
            Component cur = components.get(i);
            cur.offset = prev.endOffset;
            cur.endOffset = cur.offset + cur.length;
        }
    }

    /**
     * Remove the {@link ByteBuf} from the given index.
     *
     * @param cIndex the index on from which the {@link ByteBuf} will be remove
     */
    public CompositeByteBuf removeComponent(int cIndex) {
        checkComponentIndex(cIndex);
        components.remove(cIndex).freeIfNecessary();
        updateComponentOffsets(cIndex);
        return this;
    }

    /**
     * Remove the number of {@link ByteBuf}s starting from the given index.
     *
     * @param cIndex the index on which the {@link ByteBuf}s will be started to removed
     * @param numComponents the number of components to remove
     */
    public CompositeByteBuf removeComponents(int cIndex, int numComponents) {
        checkComponentIndex(cIndex, numComponents);

        List<Component> toRemove = components.subList(cIndex, cIndex + numComponents);
        for (Component c: toRemove) {
            c.freeIfNecessary();
        }
        toRemove.clear();

        updateComponentOffsets(cIndex);
        return this;
    }

    public Iterator<ByteBuf> iterator() {
        assert !freed;
        List<ByteBuf> list = new ArrayList<ByteBuf>(components.size());
        for (Component c: components) {
            list.add(c.buf);
        }
        return list.iterator();
    }

    /**
     * Same with {@link #slice(int, int)} except that this method returns a list.
     */
    public List<ByteBuf> decompose(int offset, int length) {
        checkIndex(offset, length);
        if (length == 0) {
            return Collections.emptyList();
        }

        int componentId = toComponentIndex(offset);
        List<ByteBuf> slice = new ArrayList<ByteBuf>(components.size());

        // The first component
        Component firstC = components.get(componentId);
        ByteBuf first = firstC.buf.duplicate();
        first.readerIndex(offset - firstC.offset);

        ByteBuf buf = first;
        int bytesToSlice = length;
        do {
            int readableBytes = buf.readableBytes();
            if (bytesToSlice <= readableBytes) {
                // Last component
                buf.writerIndex(buf.readerIndex() + bytesToSlice);
                slice.add(buf);
                break;
            } else {
                // Not the last component
                slice.add(buf);
                bytesToSlice -= readableBytes;
                componentId ++;

                // Fetch the next component.
                buf = components.get(componentId).buf.duplicate();
            }
        } while (bytesToSlice > 0);

        // Slice all components because only readable bytes are interesting.
        for (int i = 0; i < slice.size(); i ++) {
            slice.set(i, slice.get(i).slice());
        }

        return slice;
    }

    @Override
    public boolean isDirect() {
        int size = components.size();
        if (size == 0) {
            return false;
        }
        for (int i = 0; i < size; i++) {
           if (!components.get(i).buf.isDirect()) {
               return false;
           }
        }
        return true;
    }

    @Override
    public boolean hasArray() {
        if (components.size() == 1) {
            return components.get(0).buf.hasArray();
        }
        return false;
    }

    @Override
    public byte[] array() {
        if (components.size() == 1) {
            return components.get(0).buf.array();
        }
        throw new UnsupportedOperationException();
    }

    @Override
    public int arrayOffset() {
        if (components.size() == 1) {
            return components.get(0).buf.arrayOffset();
        }
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean hasMemoryAddress() {
        if (components.size() == 1) {
            return components.get(0).buf.hasMemoryAddress();
        }
        return false;
    }

    @Override
    public long memoryAddress() {
        if (components.size() == 1) {
            return components.get(0).buf.memoryAddress();
        }
        throw new UnsupportedOperationException();
    }

    @Override
    public int capacity() {
        if (components.isEmpty()) {
            return 0;
        }
        return components.get(components.size() - 1).endOffset;
    }

    @Override
    public CompositeByteBuf capacity(int newCapacity) {
        assert !freed;
        if (newCapacity < 0 || newCapacity > maxCapacity()) {
            throw new IllegalArgumentException("newCapacity: " + newCapacity);
        }

        int oldCapacity = capacity();
        if (newCapacity > oldCapacity) {
            final int paddingLength = newCapacity - oldCapacity;
            ByteBuf padding;
            int nComponents = components.size();
            if (nComponents < maxNumComponents) {
                padding = allocBuffer(paddingLength);
                padding.setIndex(0, paddingLength);
                addComponent0(components.size(), padding);
            } else {
                padding = allocBuffer(paddingLength);
                padding.setIndex(0, paddingLength);
                // FIXME: No need to create a padding buffer and consolidate.
                // Just create a big single buffer and put the current content there.
                addComponent0(components.size(), padding);
                consolidateIfNeeded();
            }
        } else if (newCapacity < oldCapacity) {
            int bytesToTrim = oldCapacity - newCapacity;
            for (ListIterator<Component> i = components.listIterator(components.size()); i.hasPrevious();) {
                Component c = i.previous();
                if (bytesToTrim >= c.length) {
                    bytesToTrim -= c.length;
                    i.remove();
                    continue;
                }

                // Replace the last component with the trimmed slice.
                Component newC = new Component(c.buf.slice(0, c.length - bytesToTrim));
                newC.offset = c.offset;
                newC.endOffset = newC.offset + newC.length;
                i.set(newC);
                break;
            }

            if (readerIndex() > newCapacity) {
                setIndex(newCapacity, newCapacity);
            } else if (writerIndex() > newCapacity) {
                writerIndex(newCapacity);
            }
        }
        return this;
    }

    @Override
    public ByteBufAllocator alloc() {
        return alloc;
    }

    @Override
    public ByteOrder order() {
        return ByteOrder.BIG_ENDIAN;
    }

    /**
     * Return the current number of {@link ByteBuf}'s that are composed in this instance
     */
    public int numComponents() {
        return components.size();
    }

    /**
     * Return the max number of {@link ByteBuf}'s that are composed in this instance
     */
    public int maxNumComponents() {
        return maxNumComponents;
    }

    /**
     * Return the index for the given offset
     */
    public int toComponentIndex(int offset) {
        assert !freed;
        checkIndex(offset);

        for (int low = 0, high = components.size(); low <= high;) {
            int mid = low + high >>> 1;
            Component c = components.get(mid);
            if (offset >= c.endOffset) {
                low = mid + 1;
            } else if (offset < c.offset) {
                high = mid - 1;
            } else {
                return mid;
            }
        }

        throw new Error("should not reach here");
    }

    public int toByteIndex(int cIndex) {
        checkComponentIndex(cIndex);
        return components.get(cIndex).offset;
    }

    @Override
    public byte getByte(int index) {
        return _getByte(index);
    }

    @Override
    protected byte _getByte(int index) {
        Component c = findComponent(index);
        return c.buf.getByte(index - c.offset);
    }

    @Override
    protected short _getShort(int index) {
        Component c = findComponent(index);
        if (index + 2 <= c.endOffset) {
            return c.buf.getShort(index - c.offset);
        } else if (order() == ByteOrder.BIG_ENDIAN) {
            return (short) ((_getByte(index) & 0xff) << 8 | _getByte(index + 1) & 0xff);
        } else {
            return (short) (_getByte(index) & 0xff | (_getByte(index + 1) & 0xff) << 8);
        }
    }

    @Override
    protected int _getUnsignedMedium(int index) {
        Component c = findComponent(index);
        if (index + 3 <= c.endOffset) {
            return c.buf.getUnsignedMedium(index - c.offset);
        } else if (order() == ByteOrder.BIG_ENDIAN) {
            return (_getShort(index) & 0xffff) << 8 | _getByte(index + 2) & 0xff;
        } else {
            return _getShort(index) & 0xFFFF | (_getByte(index + 2) & 0xFF) << 16;
        }
    }

    @Override
    protected int _getInt(int index) {
        Component c = findComponent(index);
        if (index + 4 <= c.endOffset) {
            return c.buf.getInt(index - c.offset);
        } else if (order() == ByteOrder.BIG_ENDIAN) {
            return (_getShort(index) & 0xffff) << 16 | _getShort(index + 2) & 0xffff;
        } else {
            return _getShort(index) & 0xFFFF | (_getShort(index + 2) & 0xFFFF) << 16;
        }
    }

    @Override
    protected long _getLong(int index) {
        Component c = findComponent(index);
        if (index + 8 <= c.endOffset) {
            return c.buf.getLong(index - c.offset);
        } else if (order() == ByteOrder.BIG_ENDIAN) {
            return (_getInt(index) & 0xffffffffL) << 32 | _getInt(index + 4) & 0xffffffffL;
        } else {
            return _getInt(index) & 0xFFFFFFFFL | (_getInt(index + 4) & 0xFFFFFFFFL) << 32;
        }
    }

    @Override
    public CompositeByteBuf getBytes(int index, byte[] dst, int dstIndex, int length) {
        checkDstIndex(index, length, dstIndex, dst.length);
        if (length == 0) {
            return this;
        }

        int i = toComponentIndex(index);
        while (length > 0) {
            Component c = components.get(i);
            ByteBuf s = c.buf;
            int adjustment = c.offset;
            int localLength = Math.min(length, s.capacity() - (index - adjustment));
            s.getBytes(index - adjustment, dst, dstIndex, localLength);
            index += localLength;
            dstIndex += localLength;
            length -= localLength;
            i ++;
        }
        return this;
    }

    @Override
    public CompositeByteBuf getBytes(int index, ByteBuffer dst) {
        int limit = dst.limit();
        int length = dst.remaining();

        checkIndex(index, length);
        if (length == 0) {
            return this;
        }

        int i = toComponentIndex(index);
        try {
            while (length > 0) {
                Component c = components.get(i);
                ByteBuf s = c.buf;
                int adjustment = c.offset;
                int localLength = Math.min(length, s.capacity() - (index - adjustment));
                dst.limit(dst.position() + localLength);
                s.getBytes(index - adjustment, dst);
                index += localLength;
                length -= localLength;
                i ++;
            }
        } finally {
            dst.limit(limit);
        }
        return this;
    }

    @Override
    public CompositeByteBuf getBytes(int index, ByteBuf dst, int dstIndex, int length) {
        checkDstIndex(index, length, dstIndex, dst.capacity());
        if (length == 0) {
            return this;
        }

        int i = toComponentIndex(index);
        while (length > 0) {
            Component c = components.get(i);
            ByteBuf s = c.buf;
            int adjustment = c.offset;
            int localLength = Math.min(length, s.capacity() - (index - adjustment));
            s.getBytes(index - adjustment, dst, dstIndex, localLength);
            index += localLength;
            dstIndex += localLength;
            length -= localLength;
            i ++;
        }
        return this;
    }

    @Override
    public int getBytes(int index, GatheringByteChannel out, int length)
            throws IOException {
        int count = nioBufferCount();
        if (count == 1) {
            return out.write(internalNioBuffer(index, length));
        } else {
            long writtenBytes = out.write(nioBuffers(index, length));
            if (writtenBytes > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            } else {
                return (int) writtenBytes;
            }
        }
    }

    @Override
    public CompositeByteBuf getBytes(int index, OutputStream out, int length) throws IOException {
        checkIndex(index, length);
        if (length == 0) {
            return this;
        }

        int i = toComponentIndex(index);
        while (length > 0) {
            Component c = components.get(i);
            ByteBuf s = c.buf;
            int adjustment = c.offset;
            int localLength = Math.min(length, s.capacity() - (index - adjustment));
            s.getBytes(index - adjustment, out, localLength);
            index += localLength;
            length -= localLength;
            i ++;
        }
        return this;
    }

    @Override
    public CompositeByteBuf setByte(int index, int value) {
        Component c = findComponent(index);
        c.buf.setByte(index - c.offset, value);
        return this;
    }

    @Override
    protected void _setByte(int index, int value) {
        setByte(index, value);
    }

    @Override
    public CompositeByteBuf setShort(int index, int value) {
        return (CompositeByteBuf) super.setShort(index, value);
    }

    @Override
    protected void _setShort(int index, int value) {
        Component c = findComponent(index);
        if (index + 2 <= c.endOffset) {
            c.buf.setShort(index - c.offset, value);
        } else if (order() == ByteOrder.BIG_ENDIAN) {
            _setByte(index, (byte) (value >>> 8));
            _setByte(index + 1, (byte) value);
        } else {
            _setByte(index, (byte) value);
            _setByte(index + 1, (byte) (value >>> 8));
        }
    }

    @Override
    public CompositeByteBuf setMedium(int index, int value) {
        return (CompositeByteBuf) super.setMedium(index, value);
    }

    @Override
    protected void _setMedium(int index, int value) {
        Component c = findComponent(index);
        if (index + 3 <= c.endOffset) {
            c.buf.setMedium(index - c.offset, value);
        } else if (order() == ByteOrder.BIG_ENDIAN) {
            _setShort(index, (short) (value >> 8));
            _setByte(index + 2, (byte) value);
        } else {
            _setShort(index, (short) value);
            _setByte(index + 2, (byte) (value >>> 16));
        }
    }

    @Override
    public CompositeByteBuf setInt(int index, int value) {
        return (CompositeByteBuf) super.setInt(index, value);
    }

    @Override
    protected void _setInt(int index, int value) {
        Component c = findComponent(index);
        if (index + 4 <= c.endOffset) {
            c.buf.setInt(index - c.offset, value);
        } else if (order() == ByteOrder.BIG_ENDIAN) {
            _setShort(index, (short) (value >>> 16));
            _setShort(index + 2, (short) value);
        } else {
            _setShort(index, (short) value);
            _setShort(index + 2, (short) (value >>> 16));
        }
    }

    @Override
    public CompositeByteBuf setLong(int index, long value) {
        return (CompositeByteBuf) super.setLong(index, value);
    }

    @Override
    protected void _setLong(int index, long value) {
        Component c = findComponent(index);
        if (index + 8 <= c.endOffset) {
            c.buf.setLong(index - c.offset, value);
        } else if (order() == ByteOrder.BIG_ENDIAN) {
            _setInt(index, (int) (value >>> 32));
            _setInt(index + 4, (int) value);
        } else {
            _setInt(index, (int) value);
            _setInt(index + 4, (int) (value >>> 32));
        }
    }

    @Override
    public CompositeByteBuf setBytes(int index, byte[] src, int srcIndex, int length) {
        checkSrcIndex(index, length, srcIndex, src.length);
        if (length == 0) {
            return this;
        }

        int i = toComponentIndex(index);
        while (length > 0) {
            Component c = components.get(i);
            ByteBuf s = c.buf;
            int adjustment = c.offset;
            int localLength = Math.min(length, s.capacity() - (index - adjustment));
            s.setBytes(index - adjustment, src, srcIndex, localLength);
            index += localLength;
            srcIndex += localLength;
            length -= localLength;
            i ++;
        }
        return this;
    }

    @Override
    public CompositeByteBuf setBytes(int index, ByteBuffer src) {
        int limit = src.limit();
        int length = src.remaining();

        checkIndex(index, length);
        if (length == 0) {
            return this;
        }

        int i = toComponentIndex(index);
        try {
            while (length > 0) {
                Component c = components.get(i);
                ByteBuf s = c.buf;
                int adjustment = c.offset;
                int localLength = Math.min(length, s.capacity() - (index - adjustment));
                src.limit(src.position() + localLength);
                s.setBytes(index - adjustment, src);
                index += localLength;
                length -= localLength;
                i ++;
            }
        } finally {
            src.limit(limit);
        }
        return this;
    }

    @Override
    public CompositeByteBuf setBytes(int index, ByteBuf src, int srcIndex, int length) {
        checkSrcIndex(index, length, srcIndex, src.capacity());
        if (length == 0) {
            return this;
        }

        int i = toComponentIndex(index);
        while (length > 0) {
            Component c = components.get(i);
            ByteBuf s = c.buf;
            int adjustment = c.offset;
            int localLength = Math.min(length, s.capacity() - (index - adjustment));
            s.setBytes(index - adjustment, src, srcIndex, localLength);
            index += localLength;
            srcIndex += localLength;
            length -= localLength;
            i ++;
        }
        return this;
    }

    @Override
    public int setBytes(int index, InputStream in, int length) throws IOException {
        checkIndex(index, length);
        if (length == 0) {
            return in.read(EmptyArrays.EMPTY_BYTES);
        }

        int i = toComponentIndex(index);
        int readBytes = 0;

        do {
            Component c = components.get(i);
            ByteBuf s = c.buf;
            int adjustment = c.offset;
            int localLength = Math.min(length, s.capacity() - (index - adjustment));
            int localReadBytes = s.setBytes(index - adjustment, in, localLength);
            if (localReadBytes < 0) {
                if (readBytes == 0) {
                    return -1;
                } else {
                    break;
                }
            }

            if (localReadBytes == localLength) {
                index += localLength;
                length -= localLength;
                readBytes += localLength;
                i ++;
            } else {
                index += localReadBytes;
                length -= localReadBytes;
                readBytes += localReadBytes;
            }
        } while (length > 0);

        return readBytes;
    }

    @Override
    public int setBytes(int index, ScatteringByteChannel in, int length) throws IOException {
        checkIndex(index, length);
        if (length == 0) {
            return in.read(FULL_BYTEBUFFER);
        }

        int i = toComponentIndex(index);
        int readBytes = 0;
        do {
            Component c = components.get(i);
            ByteBuf s = c.buf;
            int adjustment = c.offset;
            int localLength = Math.min(length, s.capacity() - (index - adjustment));
            int localReadBytes = s.setBytes(index - adjustment, in, localLength);

            if (localReadBytes == 0) {
                break;
            }

            if (localReadBytes < 0) {
                if (readBytes == 0) {
                    return -1;
                } else {
                    break;
                }
            }

            if (localReadBytes == localLength) {
                index += localLength;
                length -= localLength;
                readBytes += localLength;
                i ++;
            } else {
                index += localReadBytes;
                length -= localReadBytes;
                readBytes += localReadBytes;
            }
        } while (length > 0);

        return readBytes;
    }

    @Override
    public ByteBuf copy(int index, int length) {
        checkIndex(index, length);
        ByteBuf dst = Unpooled.buffer(length);
        if (length != 0) {
            copyTo(index, length, toComponentIndex(index), dst);
        }
        return dst;
    }

    private void copyTo(int index, int length, int componentId, ByteBuf dst) {
        int dstIndex = 0;
        int i = componentId;

        while (length > 0) {
            Component c = components.get(i);
            ByteBuf s = c.buf;
            int adjustment = c.offset;
            int localLength = Math.min(length, s.capacity() - (index - adjustment));
            s.getBytes(index - adjustment, dst, dstIndex, localLength);
            index += localLength;
            dstIndex += localLength;
            length -= localLength;
            i ++;
        }

        dst.writerIndex(dst.capacity());
    }

    /**
     * Return the {@link ByteBuf} on the specified index
     *
     * @param cIndex the index for which the {@link ByteBuf} should be returned
     * @return buf the {@link ByteBuf} on the specified index
     */
    public ByteBuf component(int cIndex) {
        return internalComponent(cIndex).duplicate();
    }

    /**
     * Return the {@link ByteBuf} on the specified index
     *
     * @param offset the offset for which the {@link ByteBuf} should be returned
     * @return the {@link ByteBuf} on the specified index
     */
    public ByteBuf componentAtOffset(int offset) {
        return internalComponentAtOffset(offset).duplicate();
    }

    /**
     * Return the internal {@link ByteBuf} on the specified index. Note that updating the indexes of the returned
     * buffer will lead to an undefined behavior of this buffer.
     *
     * @param cIndex the index for which the {@link ByteBuf} should be returned
     */
    public ByteBuf internalComponent(int cIndex) {
        checkComponentIndex(cIndex);
        return components.get(cIndex).buf;
    }

    /**
     * Return the internal {@link ByteBuf} on the specified offset. Note that updating the indexes of the returned
     * buffer will lead to an undefined behavior of this buffer.
     *
     * @param offset the offset for which the {@link ByteBuf} should be returned
     */
    public ByteBuf internalComponentAtOffset(int offset) {
        return findComponent(offset).buf;
    }

    private Component findComponent(int offset) {
        assert !freed;
        checkIndex(offset);

        for (int low = 0, high = components.size(); low <= high;) {
            int mid = low + high >>> 1;
            Component c = components.get(mid);
            if (offset >= c.endOffset) {
                low = mid + 1;
            } else if (offset < c.offset) {
                high = mid - 1;
            } else {
                return c;
            }
        }

        throw new Error("should not reach here");
    }

    @Override
    public int nioBufferCount() {
        if (components.size() == 1) {
            return components.get(0).buf.nioBufferCount();
        } else {
            int count = 0;
            int componentsCount = components.size();
            //noinspection ForLoopReplaceableByForEach
            for (int i = 0; i < componentsCount; i++) {
                Component c = components.get(i);
                count += c.buf.nioBufferCount();
            }
            return count;
        }
    }

    @Override
    public ByteBuffer internalNioBuffer(int index, int length) {
        if (components.size() == 1) {
            return components.get(0).buf.internalNioBuffer(index, length);
        }
        throw new UnsupportedOperationException();
    }

    @Override
    public ByteBuffer nioBuffer(int index, int length) {
        if (components.size() == 1) {
            ByteBuf buf = components.get(0).buf;
            if (buf.nioBufferCount() == 1) {
                return components.get(0).buf.nioBuffer(index, length);
            }
        }
        ByteBuffer merged = ByteBuffer.allocate(length).order(order());
        ByteBuffer[] buffers = nioBuffers(index, length);

        //noinspection ForLoopReplaceableByForEach
        for (int i = 0; i < buffers.length; i++) {
            merged.put(buffers[i]);
        }

        merged.flip();
        return merged;
    }

    @Override
    public ByteBuffer[] nioBuffers(int index, int length) {
        checkIndex(index, length);
        if (length == 0) {
            return EmptyArrays.EMPTY_BYTE_BUFFERS;
        }

        List<ByteBuffer> buffers = new ArrayList<ByteBuffer>(components.size());
        int i = toComponentIndex(index);
        while (length > 0) {
            Component c = components.get(i);
            ByteBuf s = c.buf;
            int adjustment = c.offset;
            int localLength = Math.min(length, s.capacity() - (index - adjustment));
            switch (s.nioBufferCount()) {
                case 0:
                    throw new UnsupportedOperationException();
                case 1:
                    buffers.add(s.nioBuffer(index - adjustment, localLength));
                    break;
                default:
                    Collections.addAll(buffers, s.nioBuffers(index - adjustment, localLength));
            }

            index += localLength;
            length -= localLength;
            i ++;
        }

        return buffers.toArray(new ByteBuffer[buffers.size()]);
    }

    /**
     * Consolidate the composed {@link ByteBuf}s
     */
    public CompositeByteBuf consolidate() {
        assert !freed;
        final int numComponents = numComponents();
        if (numComponents <= 1) {
            return this;
        }

        final Component last = components.get(numComponents - 1);
        final int capacity = last.endOffset;
        final ByteBuf consolidated = allocBuffer(capacity);

        for (int i = 0; i < numComponents; i ++) {
            Component c = components.get(i);
            ByteBuf b = c.buf;
            consolidated.writeBytes(b);
            c.freeIfNecessary();
        }

        components.clear();
        components.add(new Component(consolidated));
        updateComponentOffsets(0);
        return this;
    }

    /**
     * Consolidate the composed {@link ByteBuf}s
     *
     * @param cIndex the index on which to start to compose
     * @param numComponents the number of components to compose
     */
    public CompositeByteBuf consolidate(int cIndex, int numComponents) {
        checkComponentIndex(cIndex, numComponents);
        if (numComponents <= 1) {
            return this;
        }

        final int endCIndex = cIndex + numComponents;
        final Component last = components.get(endCIndex - 1);
        final int capacity = last.endOffset - components.get(cIndex).offset;
        final ByteBuf consolidated = allocBuffer(capacity);

        for (int i = cIndex; i < endCIndex; i ++) {
            Component c = components.get(i);
            ByteBuf b = c.buf;
            consolidated.writeBytes(b);
            c.freeIfNecessary();
        }

        components.subList(cIndex + 1, endCIndex).clear();
        components.set(cIndex, new Component(consolidated));
        updateComponentOffsets(cIndex);
        return this;
    }

    /**
     * Discard all {@link ByteBuf}s which are read.
     */
    public CompositeByteBuf discardReadComponents() {
        assert !freed;
        final int readerIndex = readerIndex();
        if (readerIndex == 0) {
            return this;
        }

        // Discard everything if (readerIndex = writerIndex = capacity).
        int writerIndex = writerIndex();
        if (readerIndex == writerIndex && writerIndex == capacity()) {
            for (Component c: components) {
                c.freeIfNecessary();
            }
            components.clear();
            setIndex(0, 0);
            adjustMarkers(readerIndex);
            return this;
        }

        // Remove read components.
        int firstComponentId = toComponentIndex(readerIndex);
        for (int i = 0; i < firstComponentId; i ++) {
            components.get(i).freeIfNecessary();
        }
        components.subList(0, firstComponentId).clear();

        // Update indexes and markers.
        Component first = components.get(0);
        int offset = first.offset;
        updateComponentOffsets(0);
        setIndex(readerIndex - offset, writerIndex - offset);
        adjustMarkers(offset);
        return this;
    }

    @Override
    public CompositeByteBuf discardReadBytes() {
        assert !freed;
        final int readerIndex = readerIndex();
        if (readerIndex == 0) {
            return this;
        }

        // Discard everything if (readerIndex = writerIndex = capacity).
        int writerIndex = writerIndex();
        if (readerIndex == writerIndex && writerIndex == capacity()) {
            for (Component c: components) {
                c.freeIfNecessary();
            }
            components.clear();
            setIndex(0, 0);
            adjustMarkers(readerIndex);
            return this;
        }

        // Remove read components.
        int firstComponentId = toComponentIndex(readerIndex);
        for (int i = 0; i < firstComponentId; i ++) {
            components.get(i).freeIfNecessary();
        }
        components.subList(0, firstComponentId).clear();

        // Remove or replace the first readable component with a new slice.
        Component c = components.get(0);
        int adjustment = readerIndex - c.offset;
        if (adjustment == c.length) {
            // new slice would be empty, so remove instead
            components.remove(0);
        } else {
            Component newC = new Component(c.buf.slice(adjustment, c.length - adjustment));
            components.set(0, newC);
        }

        // Update indexes and markers.
        updateComponentOffsets(0);
        setIndex(0, writerIndex - readerIndex);
        adjustMarkers(readerIndex);
        return this;
    }

    private ByteBuf allocBuffer(int capacity) {
        if (direct) {
            return alloc().directBuffer(capacity);
        }
        return alloc().heapBuffer(capacity);
    }

    @Override
    public String toString() {
        String result = super.toString();
        result = result.substring(0, result.length() - 1);
        return result + ", components=" + components.size() +  " url:" + url + ')';
    }

    private final class Component {
        final ByteBuf buf;
        final int length;
        int offset;
        int endOffset;

        Component(ByteBuf buf) {
            this.buf = buf;
            length = buf.readableBytes();
        }

        void freeIfNecessary() {
            // Unwrap so that we can free slices, too.
            buf.release(); // We should not get a NPE here. If so, it must be a bug.
        }
    }

    @Override
    public CompositeByteBuf readerIndex(int readerIndex) {
        return (CompositeByteBuf) super.readerIndex(readerIndex);
    }

    @Override
    public CompositeByteBuf writerIndex(int writerIndex) {
        return (CompositeByteBuf) super.writerIndex(writerIndex);
    }

    @Override
    public CompositeByteBuf setIndex(int readerIndex, int writerIndex) {
        return (CompositeByteBuf) super.setIndex(readerIndex, writerIndex);
    }

    @Override
    public CompositeByteBuf clear() {
        return (CompositeByteBuf) super.clear();
    }

    @Override
    public CompositeByteBuf markReaderIndex() {
        return (CompositeByteBuf) super.markReaderIndex();
    }

    @Override
    public CompositeByteBuf resetReaderIndex() {
        return (CompositeByteBuf) super.resetReaderIndex();
    }

    @Override
    public CompositeByteBuf markWriterIndex() {
        return (CompositeByteBuf) super.markWriterIndex();
    }

    @Override
    public CompositeByteBuf resetWriterIndex() {
        return (CompositeByteBuf) super.resetWriterIndex();
    }

    @Override
    public CompositeByteBuf ensureWritable(int minWritableBytes) {
        return (CompositeByteBuf) super.ensureWritable(minWritableBytes);
    }

    @Override
    public CompositeByteBuf getBytes(int index, ByteBuf dst) {
        return (CompositeByteBuf) super.getBytes(index, dst);
    }

    @Override
    public CompositeByteBuf getBytes(int index, ByteBuf dst, int length) {
        return (CompositeByteBuf) super.getBytes(index, dst, length);
    }

    @Override
    public CompositeByteBuf getBytes(int index, byte[] dst) {
        return (CompositeByteBuf) super.getBytes(index, dst);
    }

    @Override
    public CompositeByteBuf setBoolean(int index, boolean value) {
        return (CompositeByteBuf) super.setBoolean(index, value);
    }

    @Override
    public CompositeByteBuf setChar(int index, int value) {
        return (CompositeByteBuf) super.setChar(index, value);
    }

    @Override
    public CompositeByteBuf setFloat(int index, float value) {
        return (CompositeByteBuf) super.setFloat(index, value);
    }

    @Override
    public CompositeByteBuf setDouble(int index, double value) {
        return (CompositeByteBuf) super.setDouble(index, value);
    }

    @Override
    public CompositeByteBuf setBytes(int index, ByteBuf src) {
        return (CompositeByteBuf) super.setBytes(index, src);
    }

    @Override
    public CompositeByteBuf setBytes(int index, ByteBuf src, int length) {
        return (CompositeByteBuf) super.setBytes(index, src, length);
    }

    @Override
    public CompositeByteBuf setBytes(int index, byte[] src) {
        return (CompositeByteBuf) super.setBytes(index, src);
    }

    @Override
    public CompositeByteBuf setZero(int index, int length) {
        return (CompositeByteBuf) super.setZero(index, length);
    }

    @Override
    public CompositeByteBuf readBytes(ByteBuf dst) {
        return (CompositeByteBuf) super.readBytes(dst);
    }

    @Override
    public CompositeByteBuf readBytes(ByteBuf dst, int length) {
        return (CompositeByteBuf) super.readBytes(dst, length);
    }

    @Override
    public CompositeByteBuf readBytes(ByteBuf dst, int dstIndex, int length) {
        return (CompositeByteBuf) super.readBytes(dst, dstIndex, length);
    }

    @Override
    public CompositeByteBuf readBytes(byte[] dst) {
        return (CompositeByteBuf) super.readBytes(dst);
    }

    @Override
    public CompositeByteBuf readBytes(byte[] dst, int dstIndex, int length) {
        return (CompositeByteBuf) super.readBytes(dst, dstIndex, length);
    }

    @Override
    public CompositeByteBuf readBytes(ByteBuffer dst) {
        return (CompositeByteBuf) super.readBytes(dst);
    }

    @Override
    public CompositeByteBuf readBytes(OutputStream out, int length) throws IOException {
        return (CompositeByteBuf) super.readBytes(out, length);
    }

    @Override
    public CompositeByteBuf skipBytes(int length) {
        return (CompositeByteBuf) super.skipBytes(length);
    }

    @Override
    public CompositeByteBuf writeBoolean(boolean value) {
        return (CompositeByteBuf) super.writeBoolean(value);
    }

    @Override
    public CompositeByteBuf writeByte(int value) {
        return (CompositeByteBuf) super.writeByte(value);
    }

    @Override
    public CompositeByteBuf writeShort(int value) {
        return (CompositeByteBuf) super.writeShort(value);
    }

    @Override
    public CompositeByteBuf writeMedium(int value) {
        return (CompositeByteBuf) super.writeMedium(value);
    }

    @Override
    public CompositeByteBuf writeInt(int value) {
        return (CompositeByteBuf) super.writeInt(value);
    }

    @Override
    public CompositeByteBuf writeLong(long value) {
        return (CompositeByteBuf) super.writeLong(value);
    }

    @Override
    public CompositeByteBuf writeChar(int value) {
        return (CompositeByteBuf) super.writeChar(value);
    }

    @Override
    public CompositeByteBuf writeFloat(float value) {
        return (CompositeByteBuf) super.writeFloat(value);
    }

    @Override
    public CompositeByteBuf writeDouble(double value) {
        return (CompositeByteBuf) super.writeDouble(value);
    }

    @Override
    public CompositeByteBuf writeBytes(ByteBuf src) {
        return (CompositeByteBuf) super.writeBytes(src);
    }

    @Override
    public CompositeByteBuf writeBytes(ByteBuf src, int length) {
        return (CompositeByteBuf) super.writeBytes(src, length);
    }

    @Override
    public CompositeByteBuf writeBytes(ByteBuf src, int srcIndex, int length) {
        return (CompositeByteBuf) super.writeBytes(src, srcIndex, length);
    }

    @Override
    public CompositeByteBuf writeBytes(byte[] src) {
        return (CompositeByteBuf) super.writeBytes(src);
    }

    @Override
    public CompositeByteBuf writeBytes(byte[] src, int srcIndex, int length) {
        return (CompositeByteBuf) super.writeBytes(src, srcIndex, length);
    }

    @Override
    public CompositeByteBuf writeBytes(ByteBuffer src) {
        return (CompositeByteBuf) super.writeBytes(src);
    }

    @Override
    public CompositeByteBuf writeZero(int length) {
        return (CompositeByteBuf) super.writeZero(length);
    }

    @Override
    public CompositeByteBuf retain(int increment) {
        return (CompositeByteBuf) super.retain(increment);
    }

    @Override
    public CompositeByteBuf retain() {
        return (CompositeByteBuf) super.retain();
    }

    @Override
    public ByteBuffer[] nioBuffers() {
        return nioBuffers(readerIndex(), readableBytes());
    }

    @Override
    public CompositeByteBuf discardSomeReadBytes() {
        return discardReadComponents();
    }

    @Override
    protected void deallocate() {
        if (freed) {
            return;
        }

        freed = true;
        for (Component c: components) {
            c.freeIfNecessary();
        }

        if (leak != null) {
            leak.close();
        }
    }

    @Override
    public ByteBuf unwrap() {
        return null;
    }
}
