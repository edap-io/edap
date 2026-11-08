package io.edap.common.test.util;

import io.edap.util.EdapTime;
import io.edap.util.IdGenerator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class IdGeneratorTest {

	static Field timestampLeftShiftField;
	static Field workIdField;
	static Field workIdValueField;
	static Field workIdMaskField;
	static Field workIdBitsField;
	static Field sequenceMaskField;
	static Field seqBitsField;
	static Field seqField;
	static Field lastTimestampField;
	static Field historyMillsField;
	static Method setCurTimeMillisMethod;

	static {
		Class cls = IdGenerator.class;
		try {
			timestampLeftShiftField = cls.getDeclaredField("timestampLeftShift");
			workIdField             = cls.getDeclaredField("workId");
			workIdValueField        = cls.getDeclaredField("workIdValue");
			workIdMaskField         = cls.getDeclaredField("workIdMask");
			workIdBitsField         = cls.getDeclaredField("workIdBits");
			sequenceMaskField       = cls.getDeclaredField("sequenceMask");
			seqBitsField            = cls.getDeclaredField("seqBits");
			seqField                = cls.getDeclaredField("seq");
			lastTimestampField      = cls.getDeclaredField("lastTimestamp");
			historyMillsField       = cls.getDeclaredField("historyMills");

			timestampLeftShiftField.setAccessible(true);
			workIdField.setAccessible(true);
			workIdValueField.setAccessible(true);
			workIdMaskField.setAccessible(true);
			workIdBitsField.setAccessible(true);
			sequenceMaskField.setAccessible(true);
			seqBitsField.setAccessible(true);
			sequenceMaskField.setAccessible(true);
			seqField.setAccessible(true);
			lastTimestampField.setAccessible(true);
			historyMillsField.setAccessible(true);

			setCurTimeMillisMethod = cls.getDeclaredMethod("setCurTimeMillis", long.class);
			setCurTimeMillisMethod.setAccessible(true);
		} catch (NoSuchFieldException e) {
			throw new RuntimeException(e);
		} catch (NoSuchMethodException e) {
			throw new RuntimeException(e);
		}
	}

	@Test
	public void testGetTimeRewindId() throws NoSuchFieldException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
		EdapTime edapTime = EdapTime.instance();
		// 停掉后台刷新，让测试自己控制时钟
		edapTime.shutdown();

		Random random = new Random();
		int workId = random.nextInt(512);
		long oldMills = System.currentTimeMillis();
		IdGenerator idGenerator = new IdGenerator(workId);
		setCurTimeMillisMethod.invoke(idGenerator, oldMills);
		Method method = IdGenerator.class.getDeclaredMethod("getTimeRewindId", long.class, long.class);
		method.setAccessible(true);

		oldMills = System.currentTimeMillis();
		for (int i=0;i<10005;i++) {
			setCurTimeMillisMethod.invoke(idGenerator, oldMills + i);
		}

		long curMills  = oldMills - 5;
		long lastMills = oldMills + 10004;
		IdGenerator _idGenerator = idGenerator;
		InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
				() -> {
					method.invoke(_idGenerator, curMills, lastMills);
				});
		assertTrue(thrown.getTargetException().getMessage().contains("getTimeRewindId idInfoHistory.size(): 10000"));

		idGenerator = new IdGenerator(workId);
		oldMills = System.currentTimeMillis();
		setCurTimeMillisMethod.invoke(idGenerator, oldMills);
		setCurTimeMillisMethod.invoke(idGenerator, oldMills+1);
		setCurTimeMillisMethod.invoke(idGenerator, oldMills+3);
		setCurTimeMillisMethod.invoke(idGenerator, oldMills+4);
		setCurTimeMillisMethod.invoke(idGenerator, oldMills+5);

		setCurTimeMillisMethod.invoke(idGenerator, oldMills);
		idGenerator.getId();
		setCurTimeMillisMethod.invoke(idGenerator, oldMills+2);
		IdGenerator _idGenerator2 = idGenerator;
		RuntimeException thrown2 = assertThrows(RuntimeException.class,
				() -> {
					_idGenerator2.getId();
				});
		assertTrue(thrown2.getMessage().contains("getTimeRewindId error"));
	}

	@Test
	public void testGetIds() throws NoSuchFieldException, IllegalAccessException, InvocationTargetException {
		EdapTime edapTime = EdapTime.instance();
		// 停掉后台刷新，让测试自己控制时钟
		edapTime.shutdown();

		Random random = new Random();
		int workId = random.nextInt(512);
		long oldMills = System.currentTimeMillis();
		IdGenerator idGenerator = new IdGenerator(workId);
		setCurTimeMillisMethod.invoke(idGenerator, oldMills);
		Calendar cal = Calendar.getInstance();
		cal.set(2025, 1, 1, 0, 0, 0);
		cal.set(Calendar.MILLISECOND, 0);
		long expectId = ((oldMills-cal.getTimeInMillis()) << (10 + 12)) | ((workId << 12) & (-1L ^ (-1L << (10 + 12)))) | 1;

		long[] ids = idGenerator.getIds(5);
		for (int i=0;i<ids.length;i++) {
			assertEquals(expectId + i, ids[i]);
		}

		oldMills = System.currentTimeMillis();
		for (int i=0;i<10005;i++) {
			setCurTimeMillisMethod.invoke(idGenerator, oldMills + i);
		}
		System.out.println("9");
		setCurTimeMillisMethod.invoke(idGenerator, oldMills + 5000);
		long id = idGenerator.getId();
	}

	@Test
	public void testGetId() throws NoSuchFieldException, IllegalAccessException, InvocationTargetException {
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		EdapTime edapTime = EdapTime.instance();
		// 停掉后台刷新，让测试自己控制时钟
		edapTime.shutdown();
		Field curField = EdapTime.class.getDeclaredField("current");
		curField.setAccessible(true);
		Random random = new Random();
		int workId = random.nextInt(512);
		long oldMills = System.currentTimeMillis();
		IdGenerator idGenerator = new IdGenerator(workId);
		setCurTimeMillisMethod.invoke(idGenerator, oldMills);
		long id = idGenerator.getId();

		IdGenerator.IdInfo idInfo = idGenerator.idInfo(id);
		assertEquals(idInfo.getSeq(), 1);
		assertEquals(idInfo.getTimestamp(), oldMills);
		assertEquals(idInfo.getWorkId(), workId);
		assertEquals(idInfo.getSeq(), 1);

		Calendar cal = Calendar.getInstance();
		cal.set(2025, 1, 1, 0, 0, 0);
		cal.set(Calendar.MILLISECOND, 0);
		long expectId = ((oldMills-cal.getTimeInMillis()) << (10 + 12)) | ((workId << 12) & (-1L ^ (-1L << (10 + 12)))) | 1;
		assertEquals(id, expectId);
		for (int i=0;i<4095;i++) {
			if (i == 4093) {
				executor.schedule(() -> {
					try {
						curField.set(edapTime, oldMills+1);
					} catch (IllegalAccessException e) {
						throw new RuntimeException(e);
					}
				}, 1, TimeUnit.MICROSECONDS);
			}
			idGenerator.getId();

		}
		idGenerator.getId();

		assertEquals(idGenerator.getWorkId(), workId);


	}

	@Test
	public void testSetCurTimeMillis() throws NoSuchFieldException, IllegalAccessException, InterruptedException, InvocationTargetException {
		EdapTime edapTime = EdapTime.instance();
		// 停掉后台刷新，让测试自己控制时钟
		edapTime.shutdown();
		Random random = new Random();
		int workId = random.nextInt(512);
		long oldMills = System.currentTimeMillis();
		IdGenerator idGenerator = new IdGenerator(workId);
		setCurTimeMillisMethod.invoke(idGenerator, oldMills);
		assertEquals(lastTimestampField.get(idGenerator), oldMills);
		Thread.sleep(random.nextInt(500));
		long curMills = System.currentTimeMillis();
		setCurTimeMillisMethod.invoke(idGenerator, curMills);
		assertEquals(lastTimestampField.get(idGenerator), curMills);

		setCurTimeMillisMethod.invoke(idGenerator, oldMills);
		assertEquals(lastTimestampField.get(idGenerator), curMills);
	}

	@Test
	public void testConstructor() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException, NoSuchFieldException, InterruptedException {
		EdapTime edapTime = EdapTime.instance();
		// 停掉后台刷新，让测试自己控制时钟
		edapTime.shutdown();
		Random random = new Random(1024);
		int workId = random.nextInt();
		IdGeneratorTestReq testReq = new IdGeneratorTestReq();
		testReq.workId       = workId;
		testReq.workIdBits   = 10;
		testReq.seqBits      = 12;
		testReq.seqId        = 0;
		testReq.historyMills = 10*1000;
		testReq.curMills     = System.currentTimeMillis();

		IdGenerator idGenerator = new IdGenerator(() -> testReq.workId);
		setCurTimeMillisMethod.invoke(idGenerator, testReq.curMills);
		idGeneratoEquals(idGenerator, testReq);

		workId = random.nextInt();
		testReq.workId       = workId;
		idGenerator = new IdGenerator(testReq.workId);
		setCurTimeMillisMethod.invoke(idGenerator, testReq.curMills);
		idGeneratoEquals(idGenerator, testReq);

		workId = random.nextInt();
		testReq.workId       = workId;
		testReq.seqBits      = 13;
		testReq.workIdBits   = 9;
		idGenerator = new IdGenerator(testReq.seqBits, testReq.workIdBits, testReq.workId);
		setCurTimeMillisMethod.invoke(idGenerator, testReq.curMills);
		idGeneratoEquals(idGenerator, testReq);

		workId = random.nextInt();
		testReq.workId       = workId;
		testReq.seqBits      = 12;
		testReq.workIdBits   = 10;
		idGenerator = new IdGenerator(testReq.seqBits, testReq.workIdBits, () -> testReq.workId);
		setCurTimeMillisMethod.invoke(idGenerator, testReq.curMills);
		idGeneratoEquals(idGenerator, testReq);

		workId = random.nextInt();
		testReq.workId       = workId;
		testReq.seqBits      = 13;
		testReq.workIdBits   = 9;
		testReq.historyMills = 6000;
		idGenerator = new IdGenerator(testReq.seqBits, testReq.workIdBits, () -> testReq.workId, 6);
		setCurTimeMillisMethod.invoke(idGenerator, testReq.curMills);
		idGeneratoEquals(idGenerator, testReq);
	}

	private void idGeneratoEquals(IdGenerator idGenerator, IdGeneratorTestReq testReq) throws IllegalAccessException {
		long workIdMask = -1L ^ (-1L << (testReq.workIdBits + testReq.seqBits));
		assertEquals(workIdField.get(idGenerator), testReq.workId);
		assertEquals(workIdBitsField.get(idGenerator), testReq.workIdBits);
		assertEquals(workIdMaskField.get(idGenerator), workIdMask);
		assertEquals(workIdValueField.get(idGenerator), (testReq.workId << testReq.seqBits) & workIdMask);
		assertEquals(seqBitsField.get(idGenerator), testReq.seqBits);
		assertEquals(sequenceMaskField.get(idGenerator), -1L ^ (-1L << testReq.seqBits));
		assertEquals(seqField.get(idGenerator), testReq.seqId);
		assertEquals(timestampLeftShiftField.get(idGenerator), testReq.seqBits + testReq.workIdBits);
		assertEquals(historyMillsField.get(idGenerator), testReq.historyMills);
		assertEquals(lastTimestampField.get(idGenerator), testReq.curMills);
	}

	public static class IdGeneratorTestReq {
		private int  workId;
		private int  workIdBits;
		private int  seqBits;
		private long seqId;
		private int  historyMills;
		private long curMills;
	}

	/**
	 * 造一个"不主动刷新"模式的时钟源：没有推送线程，IdGenerator 得自己拉时钟。
	 */
	private static EdapTime newOnDemandTime() throws Exception {
		Constructor<EdapTime> constructor = EdapTime.class.getDeclaredConstructor(EdapTime.Mode.class);
		constructor.setAccessible(true);
		return constructor.newInstance(EdapTime.Mode.ON_DEMAND);
	}

	/**
	 * 不主动刷新模式：没有推送源，IdGenerator 每次取 ID 自己读时钟，
	 * ID 里嵌的时间戳必须是当前毫秒。
	 */
	@Test
	public void testOnDemandMode() throws Exception {
		EdapTime direct = newOnDemandTime();
		int workId = 7;
		IdGenerator idGenerator = new IdGenerator(workId, direct);

		long before = System.currentTimeMillis();
		long id = idGenerator.getId();
		long after = System.currentTimeMillis();

		IdGenerator.IdInfo info = idGenerator.idInfo(id);
		assertEquals(workId, info.getWorkId());
		assertTrue(info.getTimestamp() >= before && info.getTimestamp() <= after,
				"不主动刷新模式的 ID 时间戳必须是当前毫秒，实际 " + info.getTimestamp());
		assertEquals(1, info.getSeq());

		// 连取：ID 互不相同
		long prev = id;
		for (int i = 0; i < 100; i++) {
			long next = idGenerator.getId();
			assertNotEquals(prev, next);
			prev = next;
		}

		// 跨毫秒后时间戳要跟着走
		Thread.sleep(20L);
		long later = idGenerator.getId();
		long laterTs = idGenerator.idInfo(later).getTimestamp();
		assertTrue(laterTs > info.getTimestamp(),
				"跨毫秒后 ID 的时间戳必须前进，实际 " + laterTs + " vs " + info.getTimestamp());
	}
}
