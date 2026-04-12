package io.edap.protocol.telnet.test;

import java.util.ArrayList;
import java.util.List;

public class T {

    public static void main(String[] args) {
        Integer[] arrays = new Integer[]{5};

        List<Integer> list = new ArrayList<>();
        list.add(1);

        Integer[] array = list.toArray(arrays);
        System.out.println("array=" + array);
    }
}
