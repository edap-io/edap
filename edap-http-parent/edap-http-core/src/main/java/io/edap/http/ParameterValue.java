package io.edap.http;

import io.edap.util.StringUtil;

import java.nio.charset.StandardCharsets;

import static io.edap.util.StringUtil.fastInstance;

public class ParameterValue {

	private byte[] data;
	private String value;

	public ParameterValue(byte[] data) {
		this.setData(data);
	}

	public ParameterValue(String value) {
		if (StringUtil.isEmpty(value)) {
			this.setData(value.getBytes(StandardCharsets.UTF_8));
		}
		this.setValue(value);
	}

	public byte[] getData() {
		return data;
	}

	public void setData(byte[] data) {
		this.data = data;
	}

	public String getValue() {
		if (value == null && data != null) {
			value = fastInstance(data, (byte)0);
		}
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}
}
