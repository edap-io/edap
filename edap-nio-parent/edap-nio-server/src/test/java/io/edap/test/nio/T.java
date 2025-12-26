package io.edap.test.nio;

public class T {

	public static void main(String[] args) throws ClassNotFoundException {
		Class cls = Class.forName("sun.nio.ch.FileDispatcherImpl");
        System.out.print(cls);
	}
}
