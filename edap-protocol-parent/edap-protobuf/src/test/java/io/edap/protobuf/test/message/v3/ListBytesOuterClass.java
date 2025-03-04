// Generated by the protocol buffer compiler.  DO NOT EDIT!
// NO CHECKED-IN PROTOBUF GENCODE
// source: list_bytes.proto
// Protobuf Java Version: 4.29.3

package io.edap.protobuf.test.message.v3;

public final class ListBytesOuterClass {
  private ListBytesOuterClass() {}
  static {
    com.google.protobuf.RuntimeVersion.validateProtobufGencodeVersion(
      com.google.protobuf.RuntimeVersion.RuntimeDomain.PUBLIC,
      /* major= */ 4,
      /* minor= */ 29,
      /* patch= */ 3,
      /* suffix= */ "",
      ListBytesOuterClass.class.getName());
  }
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
    registerAllExtensions(
        (com.google.protobuf.ExtensionRegistryLite) registry);
  }
  public interface ListBytesOrBuilder extends
      // @@protoc_insertion_point(interface_extends:test.message.ListBytes)
      com.google.protobuf.MessageOrBuilder {

    /**
     * <code>repeated bytes value = 1;</code>
     * @return A list containing the value.
     */
    java.util.List<com.google.protobuf.ByteString> getValueList();
    /**
     * <code>repeated bytes value = 1;</code>
     * @return The count of value.
     */
    int getValueCount();
    /**
     * <code>repeated bytes value = 1;</code>
     * @param index The index of the element to return.
     * @return The value at the given index.
     */
    com.google.protobuf.ByteString getValue(int index);
  }
  /**
   * Protobuf type {@code test.message.ListBytes}
   */
  public static final class ListBytes extends
      com.google.protobuf.GeneratedMessage implements
      // @@protoc_insertion_point(message_implements:test.message.ListBytes)
      ListBytesOrBuilder {
  private static final long serialVersionUID = 0L;
    static {
      com.google.protobuf.RuntimeVersion.validateProtobufGencodeVersion(
        com.google.protobuf.RuntimeVersion.RuntimeDomain.PUBLIC,
        /* major= */ 4,
        /* minor= */ 29,
        /* patch= */ 3,
        /* suffix= */ "",
        ListBytes.class.getName());
    }
    // Use ListBytes.newBuilder() to construct.
    private ListBytes(com.google.protobuf.GeneratedMessage.Builder<?> builder) {
      super(builder);
    }
    private ListBytes() {
      value_ = emptyList(com.google.protobuf.ByteString.class);
    }

    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.edap.protobuf.test.message.v3.ListBytesOuterClass.internal_static_test_message_ListBytes_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessage.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.edap.protobuf.test.message.v3.ListBytesOuterClass.internal_static_test_message_ListBytes_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes.class, io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes.Builder.class);
    }

    public static final int VALUE_FIELD_NUMBER = 1;
    @SuppressWarnings("serial")
    private com.google.protobuf.Internal.ProtobufList<com.google.protobuf.ByteString> value_ =
        emptyList(com.google.protobuf.ByteString.class);
    /**
     * <code>repeated bytes value = 1;</code>
     * @return A list containing the value.
     */
    @java.lang.Override
    public java.util.List<com.google.protobuf.ByteString>
        getValueList() {
      return value_;
    }
    /**
     * <code>repeated bytes value = 1;</code>
     * @return The count of value.
     */
    public int getValueCount() {
      return value_.size();
    }
    /**
     * <code>repeated bytes value = 1;</code>
     * @param index The index of the element to return.
     * @return The value at the given index.
     */
    public com.google.protobuf.ByteString getValue(int index) {
      return value_.get(index);
    }

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
      for (int i = 0; i < value_.size(); i++) {
        output.writeBytes(1, value_.get(i));
      }
      getUnknownFields().writeTo(output);
    }

    @java.lang.Override
    public int getSerializedSize() {
      int size = memoizedSize;
      if (size != -1) return size;

      size = 0;
      {
        int dataSize = 0;
        for (int i = 0; i < value_.size(); i++) {
          dataSize += com.google.protobuf.CodedOutputStream
            .computeBytesSizeNoTag(value_.get(i));
        }
        size += dataSize;
        size += 1 * getValueList().size();
      }
      size += getUnknownFields().getSerializedSize();
      memoizedSize = size;
      return size;
    }

    @java.lang.Override
    public boolean equals(final java.lang.Object obj) {
      if (obj == this) {
       return true;
      }
      if (!(obj instanceof io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes)) {
        return super.equals(obj);
      }
      io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes other = (io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes) obj;

      if (!getValueList()
          .equals(other.getValueList())) return false;
      if (!getUnknownFields().equals(other.getUnknownFields())) return false;
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
      hash = (29 * hash) + getUnknownFields().hashCode();
      memoizedHashCode = hash;
      return hash;
    }

    public static io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes parseFrom(
        java.nio.ByteBuffer data)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes parseFrom(
        java.nio.ByteBuffer data,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes parseFrom(
        com.google.protobuf.ByteString data)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes parseFrom(
        com.google.protobuf.ByteString data,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes parseFrom(byte[] data)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes parseFrom(
        byte[] data,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes parseFrom(java.io.InputStream input)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessage
          .parseWithIOException(PARSER, input);
    }
    public static io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes parseFrom(
        java.io.InputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessage
          .parseWithIOException(PARSER, input, extensionRegistry);
    }

    public static io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes parseDelimitedFrom(java.io.InputStream input)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessage
          .parseDelimitedWithIOException(PARSER, input);
    }

    public static io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes parseDelimitedFrom(
        java.io.InputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessage
          .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
    }
    public static io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes parseFrom(
        com.google.protobuf.CodedInputStream input)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessage
          .parseWithIOException(PARSER, input);
    }
    public static io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes parseFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessage
          .parseWithIOException(PARSER, input, extensionRegistry);
    }

    @java.lang.Override
    public Builder newBuilderForType() { return newBuilder(); }
    public static Builder newBuilder() {
      return DEFAULT_INSTANCE.toBuilder();
    }
    public static Builder newBuilder(io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes prototype) {
      return DEFAULT_INSTANCE.toBuilder().mergeFrom(prototype);
    }
    @java.lang.Override
    public Builder toBuilder() {
      return this == DEFAULT_INSTANCE
          ? new Builder() : new Builder().mergeFrom(this);
    }

    @java.lang.Override
    protected Builder newBuilderForType(
        com.google.protobuf.GeneratedMessage.BuilderParent parent) {
      Builder builder = new Builder(parent);
      return builder;
    }
    /**
     * Protobuf type {@code test.message.ListBytes}
     */
    public static final class Builder extends
        com.google.protobuf.GeneratedMessage.Builder<Builder> implements
        // @@protoc_insertion_point(builder_implements:test.message.ListBytes)
        io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytesOrBuilder {
      public static final com.google.protobuf.Descriptors.Descriptor
          getDescriptor() {
        return io.edap.protobuf.test.message.v3.ListBytesOuterClass.internal_static_test_message_ListBytes_descriptor;
      }

      @java.lang.Override
      protected com.google.protobuf.GeneratedMessage.FieldAccessorTable
          internalGetFieldAccessorTable() {
        return io.edap.protobuf.test.message.v3.ListBytesOuterClass.internal_static_test_message_ListBytes_fieldAccessorTable
            .ensureFieldAccessorsInitialized(
                io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes.class, io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes.Builder.class);
      }

      // Construct using io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes.newBuilder()
      private Builder() {

      }

      private Builder(
          com.google.protobuf.GeneratedMessage.BuilderParent parent) {
        super(parent);

      }
      @java.lang.Override
      public Builder clear() {
        super.clear();
        bitField0_ = 0;
        value_ = emptyList(com.google.protobuf.ByteString.class);
        return this;
      }

      @java.lang.Override
      public com.google.protobuf.Descriptors.Descriptor
          getDescriptorForType() {
        return io.edap.protobuf.test.message.v3.ListBytesOuterClass.internal_static_test_message_ListBytes_descriptor;
      }

      @java.lang.Override
      public io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes getDefaultInstanceForType() {
        return io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes.getDefaultInstance();
      }

      @java.lang.Override
      public io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes build() {
        io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes result = buildPartial();
        if (!result.isInitialized()) {
          throw newUninitializedMessageException(result);
        }
        return result;
      }

      @java.lang.Override
      public io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes buildPartial() {
        io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes result = new io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes(this);
        if (bitField0_ != 0) { buildPartial0(result); }
        onBuilt();
        return result;
      }

      private void buildPartial0(io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes result) {
        int from_bitField0_ = bitField0_;
        if (((from_bitField0_ & 0x00000001) != 0)) {
          value_.makeImmutable();
          result.value_ = value_;
        }
      }

      @java.lang.Override
      public Builder mergeFrom(com.google.protobuf.Message other) {
        if (other instanceof io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes) {
          return mergeFrom((io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes)other);
        } else {
          super.mergeFrom(other);
          return this;
        }
      }

      public Builder mergeFrom(io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes other) {
        if (other == io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes.getDefaultInstance()) return this;
        if (!other.value_.isEmpty()) {
          if (value_.isEmpty()) {
            value_ = other.value_;
            value_.makeImmutable();
            bitField0_ |= 0x00000001;
          } else {
            ensureValueIsMutable();
            value_.addAll(other.value_);
          }
          onChanged();
        }
        this.mergeUnknownFields(other.getUnknownFields());
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
        if (extensionRegistry == null) {
          throw new java.lang.NullPointerException();
        }
        try {
          boolean done = false;
          while (!done) {
            int tag = input.readTag();
            switch (tag) {
              case 0:
                done = true;
                break;
              case 10: {
                com.google.protobuf.ByteString v = input.readBytes();
                ensureValueIsMutable();
                value_.add(v);
                break;
              } // case 10
              default: {
                if (!super.parseUnknownField(input, extensionRegistry, tag)) {
                  done = true; // was an endgroup tag
                }
                break;
              } // default:
            } // switch (tag)
          } // while (!done)
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
          throw e.unwrapIOException();
        } finally {
          onChanged();
        } // finally
        return this;
      }
      private int bitField0_;

      private com.google.protobuf.Internal.ProtobufList<com.google.protobuf.ByteString> value_ = emptyList(com.google.protobuf.ByteString.class);
      private void ensureValueIsMutable() {
        if (!value_.isModifiable()) {
          value_ = makeMutableCopy(value_);
        }
        bitField0_ |= 0x00000001;
      }
      /**
       * <code>repeated bytes value = 1;</code>
       * @return A list containing the value.
       */
      public java.util.List<com.google.protobuf.ByteString>
          getValueList() {
        value_.makeImmutable();
        return value_;
      }
      /**
       * <code>repeated bytes value = 1;</code>
       * @return The count of value.
       */
      public int getValueCount() {
        return value_.size();
      }
      /**
       * <code>repeated bytes value = 1;</code>
       * @param index The index of the element to return.
       * @return The value at the given index.
       */
      public com.google.protobuf.ByteString getValue(int index) {
        return value_.get(index);
      }
      /**
       * <code>repeated bytes value = 1;</code>
       * @param index The index to set the value at.
       * @param value The value to set.
       * @return This builder for chaining.
       */
      public Builder setValue(
          int index, com.google.protobuf.ByteString value) {
        if (value == null) { throw new NullPointerException(); }
        ensureValueIsMutable();
        value_.set(index, value);
        bitField0_ |= 0x00000001;
        onChanged();
        return this;
      }
      /**
       * <code>repeated bytes value = 1;</code>
       * @param value The value to add.
       * @return This builder for chaining.
       */
      public Builder addValue(com.google.protobuf.ByteString value) {
        if (value == null) { throw new NullPointerException(); }
        ensureValueIsMutable();
        value_.add(value);
        bitField0_ |= 0x00000001;
        onChanged();
        return this;
      }
      /**
       * <code>repeated bytes value = 1;</code>
       * @param values The value to add.
       * @return This builder for chaining.
       */
      public Builder addAllValue(
          java.lang.Iterable<? extends com.google.protobuf.ByteString> values) {
        ensureValueIsMutable();
        com.google.protobuf.AbstractMessageLite.Builder.addAll(
            values, value_);
        bitField0_ |= 0x00000001;
        onChanged();
        return this;
      }
      /**
       * <code>repeated bytes value = 1;</code>
       * @return This builder for chaining.
       */
      public Builder clearValue() {
        value_ = emptyList(com.google.protobuf.ByteString.class);
        bitField0_ = (bitField0_ & ~0x00000001);
        onChanged();
        return this;
      }

      // @@protoc_insertion_point(builder_scope:test.message.ListBytes)
    }

    // @@protoc_insertion_point(class_scope:test.message.ListBytes)
    private static final io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes DEFAULT_INSTANCE;
    static {
      DEFAULT_INSTANCE = new io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes();
    }

    public static io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes getDefaultInstance() {
      return DEFAULT_INSTANCE;
    }

    private static final com.google.protobuf.Parser<ListBytes>
        PARSER = new com.google.protobuf.AbstractParser<ListBytes>() {
      @java.lang.Override
      public ListBytes parsePartialFrom(
          com.google.protobuf.CodedInputStream input,
          com.google.protobuf.ExtensionRegistryLite extensionRegistry)
          throws com.google.protobuf.InvalidProtocolBufferException {
        Builder builder = newBuilder();
        try {
          builder.mergeFrom(input, extensionRegistry);
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
          throw e.setUnfinishedMessage(builder.buildPartial());
        } catch (com.google.protobuf.UninitializedMessageException e) {
          throw e.asInvalidProtocolBufferException().setUnfinishedMessage(builder.buildPartial());
        } catch (java.io.IOException e) {
          throw new com.google.protobuf.InvalidProtocolBufferException(e)
              .setUnfinishedMessage(builder.buildPartial());
        }
        return builder.buildPartial();
      }
    };

    public static com.google.protobuf.Parser<ListBytes> parser() {
      return PARSER;
    }

    @java.lang.Override
    public com.google.protobuf.Parser<ListBytes> getParserForType() {
      return PARSER;
    }

    @java.lang.Override
    public io.edap.protobuf.test.message.v3.ListBytesOuterClass.ListBytes getDefaultInstanceForType() {
      return DEFAULT_INSTANCE;
    }

  }

  private static final com.google.protobuf.Descriptors.Descriptor
    internal_static_test_message_ListBytes_descriptor;
  private static final 
    com.google.protobuf.GeneratedMessage.FieldAccessorTable
      internal_static_test_message_ListBytes_fieldAccessorTable;

  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n\020list_bytes.proto\022\014test.message\"\032\n\tList" +
      "Bytes\022\r\n\005value\030\001 \003(\014B\"\n io.edap.protobuf" +
      ".test.message.v3b\006proto3"
    };
    descriptor = com.google.protobuf.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new com.google.protobuf.Descriptors.FileDescriptor[] {
        });
    internal_static_test_message_ListBytes_descriptor =
      getDescriptor().getMessageTypes().get(0);
    internal_static_test_message_ListBytes_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessage.FieldAccessorTable(
        internal_static_test_message_ListBytes_descriptor,
        new java.lang.String[] { "Value", });
    descriptor.resolveAllFeaturesImmutable();
  }

  // @@protoc_insertion_point(outer_class_scope)
}
