// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: list_sfixed64.proto

package io.edap.protobuf.test.message.v3;

public final class ListSfixed64OuterClass {
  private ListSfixed64OuterClass() {}
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
    registerAllExtensions(
        (com.google.protobuf.ExtensionRegistryLite) registry);
  }
  public interface ListSfixed64OrBuilder extends
      // @@protoc_insertion_point(interface_extends:test.message.ListSfixed64)
      com.google.protobuf.MessageOrBuilder {

    /**
     * <code>repeated sfixed64 value = 1;</code>
     * @return A list containing the value.
     */
    java.util.List<java.lang.Long> getValueList();
    /**
     * <code>repeated sfixed64 value = 1;</code>
     * @return The count of value.
     */
    int getValueCount();
    /**
     * <code>repeated sfixed64 value = 1;</code>
     * @param index The index of the element to return.
     * @return The value at the given index.
     */
    long getValue(int index);
  }
  /**
   * Protobuf type {@code test.message.ListSfixed64}
   */
  public  static final class ListSfixed64 extends
      com.google.protobuf.GeneratedMessageV3 implements
      // @@protoc_insertion_point(message_implements:test.message.ListSfixed64)
      ListSfixed64OrBuilder {
  private static final long serialVersionUID = 0L;
    // Use ListSfixed64.newBuilder() to construct.
    private ListSfixed64(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
      super(builder);
    }
    private ListSfixed64() {
      value_ = emptyLongList();
    }

    @java.lang.Override
    @SuppressWarnings({"unused"})
    protected java.lang.Object newInstance(
        UnusedPrivateParameter unused) {
      return new ListSfixed64();
    }

    @java.lang.Override
    public final com.google.protobuf.UnknownFieldSet
    getUnknownFields() {
      return this.unknownFields;
    }
    private ListSfixed64(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      this();
      if (extensionRegistry == null) {
        throw new java.lang.NullPointerException();
      }
      int mutable_bitField0_ = 0;
      com.google.protobuf.UnknownFieldSet.Builder unknownFields =
          com.google.protobuf.UnknownFieldSet.newBuilder();
      try {
        boolean done = false;
        while (!done) {
          int tag = input.readTag();
          switch (tag) {
            case 0:
              done = true;
              break;
            case 9: {
              if (!((mutable_bitField0_ & 0x00000001) != 0)) {
                value_ = newLongList();
                mutable_bitField0_ |= 0x00000001;
              }
              value_.addLong(input.readSFixed64());
              break;
            }
            case 10: {
              int length = input.readRawVarint32();
              int limit = input.pushLimit(length);
              if (!((mutable_bitField0_ & 0x00000001) != 0) && input.getBytesUntilLimit() > 0) {
                value_ = newLongList();
                mutable_bitField0_ |= 0x00000001;
              }
              while (input.getBytesUntilLimit() > 0) {
                value_.addLong(input.readSFixed64());
              }
              input.popLimit(limit);
              break;
            }
            default: {
              if (!parseUnknownField(
                  input, unknownFields, extensionRegistry, tag)) {
                done = true;
              }
              break;
            }
          }
        }
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        throw e.setUnfinishedMessage(this);
      } catch (java.io.IOException e) {
        throw new com.google.protobuf.InvalidProtocolBufferException(
            e).setUnfinishedMessage(this);
      } finally {
        if (((mutable_bitField0_ & 0x00000001) != 0)) {
          value_.makeImmutable(); // C
        }
        this.unknownFields = unknownFields.build();
        makeExtensionsImmutable();
      }
    }
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.internal_static_test_message_ListSfixed64_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.internal_static_test_message_ListSfixed64_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64.class, io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64.Builder.class);
    }

    public static final int VALUE_FIELD_NUMBER = 1;
    private com.google.protobuf.Internal.LongList value_;
    /**
     * <code>repeated sfixed64 value = 1;</code>
     * @return A list containing the value.
     */
    public java.util.List<java.lang.Long>
        getValueList() {
      return value_;
    }
    /**
     * <code>repeated sfixed64 value = 1;</code>
     * @return The count of value.
     */
    public int getValueCount() {
      return value_.size();
    }
    /**
     * <code>repeated sfixed64 value = 1;</code>
     * @param index The index of the element to return.
     * @return The value at the given index.
     */
    public long getValue(int index) {
      return value_.getLong(index);
    }
    private int valueMemoizedSerializedSize = -1;

    private byte memoizedIsInitialized = -1;
    @java.lang.Override
    public final boolean isInitialized() {
      byte isInitialized = memoizedIsInitialized;
      if (isInitialized == 1) return true;
      if (isInitialized == 0) return false;

      memoizedIsInitialized = 1;
      return true;
    }

    @java.lang.Override
    public void writeTo(com.google.protobuf.CodedOutputStream output)
                        throws java.io.IOException {
      getSerializedSize();
      if (getValueList().size() > 0) {
        output.writeUInt32NoTag(10);
        output.writeUInt32NoTag(valueMemoizedSerializedSize);
      }
      for (int i = 0; i < value_.size(); i++) {
        output.writeSFixed64NoTag(value_.getLong(i));
      }
      unknownFields.writeTo(output);
    }

    @java.lang.Override
    public int getSerializedSize() {
      int size = memoizedSize;
      if (size != -1) return size;

      size = 0;
      {
        int dataSize = 0;
        dataSize = 8 * getValueList().size();
        size += dataSize;
        if (!getValueList().isEmpty()) {
          size += 1;
          size += com.google.protobuf.CodedOutputStream
              .computeInt32SizeNoTag(dataSize);
        }
        valueMemoizedSerializedSize = dataSize;
      }
      size += unknownFields.getSerializedSize();
      memoizedSize = size;
      return size;
    }

    @java.lang.Override
    public boolean equals(final java.lang.Object obj) {
      if (obj == this) {
       return true;
      }
      if (!(obj instanceof io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64)) {
        return super.equals(obj);
      }
      io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64 other = (io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64) obj;

      if (!getValueList()
          .equals(other.getValueList())) return false;
      if (!unknownFields.equals(other.unknownFields)) return false;
      return true;
    }

    @java.lang.Override
    public int hashCode() {
      if (memoizedHashCode != 0) {
        return memoizedHashCode;
      }
      int hash = 41;
      hash = (19 * hash) + getDescriptor().hashCode();
      if (getValueCount() > 0) {
        hash = (37 * hash) + VALUE_FIELD_NUMBER;
        hash = (53 * hash) + getValueList().hashCode();
      }
      hash = (29 * hash) + unknownFields.hashCode();
      memoizedHashCode = hash;
      return hash;
    }

    public static io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64 parseFrom(
        java.nio.ByteBuffer data)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64 parseFrom(
        java.nio.ByteBuffer data,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64 parseFrom(
        com.google.protobuf.ByteString data)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64 parseFrom(
        com.google.protobuf.ByteString data,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64 parseFrom(byte[] data)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64 parseFrom(
        byte[] data,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64 parseFrom(java.io.InputStream input)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessageV3
          .parseWithIOException(PARSER, input);
    }
    public static io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64 parseFrom(
        java.io.InputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessageV3
          .parseWithIOException(PARSER, input, extensionRegistry);
    }
    public static io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64 parseDelimitedFrom(java.io.InputStream input)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessageV3
          .parseDelimitedWithIOException(PARSER, input);
    }
    public static io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64 parseDelimitedFrom(
        java.io.InputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessageV3
          .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
    }
    public static io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64 parseFrom(
        com.google.protobuf.CodedInputStream input)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessageV3
          .parseWithIOException(PARSER, input);
    }
    public static io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64 parseFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessageV3
          .parseWithIOException(PARSER, input, extensionRegistry);
    }

    @java.lang.Override
    public Builder newBuilderForType() { return newBuilder(); }
    public static Builder newBuilder() {
      return DEFAULT_INSTANCE.toBuilder();
    }
    public static Builder newBuilder(io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64 prototype) {
      return DEFAULT_INSTANCE.toBuilder().mergeFrom(prototype);
    }
    @java.lang.Override
    public Builder toBuilder() {
      return this == DEFAULT_INSTANCE
          ? new Builder() : new Builder().mergeFrom(this);
    }

    @java.lang.Override
    protected Builder newBuilderForType(
        com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
      Builder builder = new Builder(parent);
      return builder;
    }
    /**
     * Protobuf type {@code test.message.ListSfixed64}
     */
    public static final class Builder extends
        com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
        // @@protoc_insertion_point(builder_implements:test.message.ListSfixed64)
        io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64OrBuilder {
      public static final com.google.protobuf.Descriptors.Descriptor
          getDescriptor() {
        return io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.internal_static_test_message_ListSfixed64_descriptor;
      }

      @java.lang.Override
      protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
          internalGetFieldAccessorTable() {
        return io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.internal_static_test_message_ListSfixed64_fieldAccessorTable
            .ensureFieldAccessorsInitialized(
                io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64.class, io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64.Builder.class);
      }

      // Construct using io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64.newBuilder()
      private Builder() {
        maybeForceBuilderInitialization();
      }

      private Builder(
          com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
        super(parent);
        maybeForceBuilderInitialization();
      }
      private void maybeForceBuilderInitialization() {
        if (com.google.protobuf.GeneratedMessageV3
                .alwaysUseFieldBuilders) {
        }
      }
      @java.lang.Override
      public Builder clear() {
        super.clear();
        value_ = emptyLongList();
        bitField0_ = (bitField0_ & ~0x00000001);
        return this;
      }

      @java.lang.Override
      public com.google.protobuf.Descriptors.Descriptor
          getDescriptorForType() {
        return io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.internal_static_test_message_ListSfixed64_descriptor;
      }

      @java.lang.Override
      public io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64 getDefaultInstanceForType() {
        return io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64.getDefaultInstance();
      }

      @java.lang.Override
      public io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64 build() {
        io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64 result = buildPartial();
        if (!result.isInitialized()) {
          throw newUninitializedMessageException(result);
        }
        return result;
      }

      @java.lang.Override
      public io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64 buildPartial() {
        io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64 result = new io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64(this);
        int from_bitField0_ = bitField0_;
        if (((bitField0_ & 0x00000001) != 0)) {
          value_.makeImmutable();
          bitField0_ = (bitField0_ & ~0x00000001);
        }
        result.value_ = value_;
        onBuilt();
        return result;
      }

      @java.lang.Override
      public Builder clone() {
        return super.clone();
      }
      @java.lang.Override
      public Builder setField(
          com.google.protobuf.Descriptors.FieldDescriptor field,
          java.lang.Object value) {
        return super.setField(field, value);
      }
      @java.lang.Override
      public Builder clearField(
          com.google.protobuf.Descriptors.FieldDescriptor field) {
        return super.clearField(field);
      }
      @java.lang.Override
      public Builder clearOneof(
          com.google.protobuf.Descriptors.OneofDescriptor oneof) {
        return super.clearOneof(oneof);
      }
      @java.lang.Override
      public Builder setRepeatedField(
          com.google.protobuf.Descriptors.FieldDescriptor field,
          int index, java.lang.Object value) {
        return super.setRepeatedField(field, index, value);
      }
      @java.lang.Override
      public Builder addRepeatedField(
          com.google.protobuf.Descriptors.FieldDescriptor field,
          java.lang.Object value) {
        return super.addRepeatedField(field, value);
      }
      @java.lang.Override
      public Builder mergeFrom(com.google.protobuf.Message other) {
        if (other instanceof io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64) {
          return mergeFrom((io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64)other);
        } else {
          super.mergeFrom(other);
          return this;
        }
      }

      public Builder mergeFrom(io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64 other) {
        if (other == io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64.getDefaultInstance()) return this;
        if (!other.value_.isEmpty()) {
          if (value_.isEmpty()) {
            value_ = other.value_;
            bitField0_ = (bitField0_ & ~0x00000001);
          } else {
            ensureValueIsMutable();
            value_.addAll(other.value_);
          }
          onChanged();
        }
        this.mergeUnknownFields(other.unknownFields);
        onChanged();
        return this;
      }

      @java.lang.Override
      public final boolean isInitialized() {
        return true;
      }

      @java.lang.Override
      public Builder mergeFrom(
          com.google.protobuf.CodedInputStream input,
          com.google.protobuf.ExtensionRegistryLite extensionRegistry)
          throws java.io.IOException {
        io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64 parsedMessage = null;
        try {
          parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
          parsedMessage = (io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64) e.getUnfinishedMessage();
          throw e.unwrapIOException();
        } finally {
          if (parsedMessage != null) {
            mergeFrom(parsedMessage);
          }
        }
        return this;
      }
      private int bitField0_;

      private com.google.protobuf.Internal.LongList value_ = emptyLongList();
      private void ensureValueIsMutable() {
        if (!((bitField0_ & 0x00000001) != 0)) {
          value_ = mutableCopy(value_);
          bitField0_ |= 0x00000001;
         }
      }
      /**
       * <code>repeated sfixed64 value = 1;</code>
       * @return A list containing the value.
       */
      public java.util.List<java.lang.Long>
          getValueList() {
        return ((bitField0_ & 0x00000001) != 0) ?
                 java.util.Collections.unmodifiableList(value_) : value_;
      }
      /**
       * <code>repeated sfixed64 value = 1;</code>
       * @return The count of value.
       */
      public int getValueCount() {
        return value_.size();
      }
      /**
       * <code>repeated sfixed64 value = 1;</code>
       * @param index The index of the element to return.
       * @return The value at the given index.
       */
      public long getValue(int index) {
        return value_.getLong(index);
      }
      /**
       * <code>repeated sfixed64 value = 1;</code>
       * @param index The index to set the value at.
       * @param value The value to set.
       * @return This builder for chaining.
       */
      public Builder setValue(
          int index, long value) {
        ensureValueIsMutable();
        value_.setLong(index, value);
        onChanged();
        return this;
      }
      /**
       * <code>repeated sfixed64 value = 1;</code>
       * @param value The value to add.
       * @return This builder for chaining.
       */
      public Builder addValue(long value) {
        ensureValueIsMutable();
        value_.addLong(value);
        onChanged();
        return this;
      }
      /**
       * <code>repeated sfixed64 value = 1;</code>
       * @param values The value to add.
       * @return This builder for chaining.
       */
      public Builder addAllValue(
          java.lang.Iterable<? extends java.lang.Long> values) {
        ensureValueIsMutable();
        com.google.protobuf.AbstractMessageLite.Builder.addAll(
            values, value_);
        onChanged();
        return this;
      }
      /**
       * <code>repeated sfixed64 value = 1;</code>
       * @return This builder for chaining.
       */
      public Builder clearValue() {
        value_ = emptyLongList();
        bitField0_ = (bitField0_ & ~0x00000001);
        onChanged();
        return this;
      }
      @java.lang.Override
      public final Builder setUnknownFields(
          final com.google.protobuf.UnknownFieldSet unknownFields) {
        return super.setUnknownFields(unknownFields);
      }

      @java.lang.Override
      public final Builder mergeUnknownFields(
          final com.google.protobuf.UnknownFieldSet unknownFields) {
        return super.mergeUnknownFields(unknownFields);
      }


      // @@protoc_insertion_point(builder_scope:test.message.ListSfixed64)
    }

    // @@protoc_insertion_point(class_scope:test.message.ListSfixed64)
    private static final io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64 DEFAULT_INSTANCE;
    static {
      DEFAULT_INSTANCE = new io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64();
    }

    public static io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64 getDefaultInstance() {
      return DEFAULT_INSTANCE;
    }

    private static final com.google.protobuf.Parser<ListSfixed64>
        PARSER = new com.google.protobuf.AbstractParser<ListSfixed64>() {
      @java.lang.Override
      public ListSfixed64 parsePartialFrom(
          com.google.protobuf.CodedInputStream input,
          com.google.protobuf.ExtensionRegistryLite extensionRegistry)
          throws com.google.protobuf.InvalidProtocolBufferException {
        return new ListSfixed64(input, extensionRegistry);
      }
    };

    public static com.google.protobuf.Parser<ListSfixed64> parser() {
      return PARSER;
    }

    @java.lang.Override
    public com.google.protobuf.Parser<ListSfixed64> getParserForType() {
      return PARSER;
    }

    @java.lang.Override
    public io.edap.protobuf.test.message.v3.ListSfixed64OuterClass.ListSfixed64 getDefaultInstanceForType() {
      return DEFAULT_INSTANCE;
    }

  }

  private static final com.google.protobuf.Descriptors.Descriptor
    internal_static_test_message_ListSfixed64_descriptor;
  private static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_test_message_ListSfixed64_fieldAccessorTable;

  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n\023list_sfixed64.proto\022\014test.message\"\035\n\014L" +
      "istSfixed64\022\r\n\005value\030\001 \003(\020B\"\n io.edap.pr" +
      "otobuf.test.message.v3b\006proto3"
    };
    descriptor = com.google.protobuf.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new com.google.protobuf.Descriptors.FileDescriptor[] {
        });
    internal_static_test_message_ListSfixed64_descriptor =
      getDescriptor().getMessageTypes().get(0);
    internal_static_test_message_ListSfixed64_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_test_message_ListSfixed64_descriptor,
        new java.lang.String[] { "Value", });
  }

  // @@protoc_insertion_point(outer_class_scope)
}