package io.edap.concurrent;

/**
 * 参考conversantmedia/distuptor的一个高性能队列的接口
 */
public interface ConcurrentQueue<E> {

    /**
     * 向ring中添加一个元素
     * @param e 需要添加到ring中的元素
     * @return 是否添加成功，成功返回true否则返回false
     */
    boolean offer(E e);

    /**
     * 从对列头部移除一个元素
     * @return T 如果队列非空返回队列头部的元素否则返回null
     */
    E poll();

    /**
     * 返回队列头部的元素
     * @return E 如果队列非空返回元素，否则返回null
     */
    E peek();

    /**
     * 队列中元素的个数
     * @return 元素的个数
     */
    int size();

    /**
     * 队列的容量
     * @return
     */
    int capacity();

    /**
     * 队列是否为空
     * @return 为空返回true，否则返回false
     */
    boolean isEmpty();

    /**
     * 队列中是否包含指定的原色
     * @param o 需要判断是否在队列中存在的元素
     * @return 如果存在返回true，否则返回false
     */
    boolean contains(Object o);

    /**
     * 从队列头部按先后顺序批量移除元素，移除最大的数量为给定的数组的大小
     * @param e 保存移除元素的数组
     * @return 返回移除的元素个数，如果队列中的元素个数小于数组大小事返回队列中元素的个数
     */
    int remove(E[] e);

    /**
     * 清空队列中的所有元素
     */
    void clear();

}
