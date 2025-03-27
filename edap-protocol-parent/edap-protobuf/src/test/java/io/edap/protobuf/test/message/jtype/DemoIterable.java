package io.edap.protobuf.test.message.jtype;

import java.util.Iterator;

public class DemoIterable<T> implements Iterable<T> {

    private T[] values;

    public DemoIterable(T[] values) {
        this.values = values;
    }

    @Override
    public Iterator<T> iterator() {
        return new DemoIterator();
    }

    public class DemoIterator implements Iterator<T> {

        private int index = 0;

        @Override
        public boolean hasNext() {
            return index < values.length;
        }

        @Override
        public T next() {
            return values[index++];
        }
    }
}
