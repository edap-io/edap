// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: list_message.proto

package io.edap.protobuf.test.message.v3;

public final class ListMessageOuterClass {
  private ListMessageOuterClass() {}
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
    registerAllExtensions(
        (com.google.protobuf.ExtensionRegistryLite) registry);
  }
  public interface ListMessageOrBuilder extends
      // @@protoc_insertion_point(interface_extends:test.message.ListMessage)
      com.google.protobuf.MessageOrBuilder {

    /**
     * <code>repeated .test.message.Proj value = 1;</code>
     */
    java.util.List<io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj> 
        getValueList();
    /**
     * <code>repeated .test.message.Proj value = 1;</code>
     */
    io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj getValue(int index);
    /**
     * <code>repeated .test.message.Proj value = 1;</code>
     */
    int getValueCount();
    /**
     * <code>repeated .test.message.Proj value = 1;</code>
     */
    java.util.List<? extends io.edap.protobuf.test.message.v3.OneMessageOuterClass.ProjOrBuilder> 
        getValueOrBuilderList();
    /**
     * <code>repeated .test.message.Proj value = 1;</code>
     */
    io.edap.protobuf.test.message.v3.OneMessageOuterClass.ProjOrBuilder getValueOrBuilder(
        int index);
  }
  /**
   * Protobuf type {@code test.message.ListMessage}
   */
  public  static final class ListMessage extends
      com.google.protobuf.GeneratedMessageV3 implements
      // @@protoc_insertion_point(message_implements:test.message.ListMessage)
      ListMessageOrBuilder {
  private static final long serialVersionUID = 0L;
    // Use ListMessage.newBuilder() to construct.
    private ListMessage(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
      super(builder);
    }
    private ListMessage() {
      value_ = java.util.Collections.emptyList();
    }

    @java.lang.Override
    @SuppressWarnings({"unused"})
    protected java.lang.Object newInstance(
        UnusedPrivateParameter unused) {
      return new ListMessage();
    }

    @java.lang.Override
    public final com.google.protobuf.UnknownFieldSet
    getUnknownFields() {
      return this.unknownFields;
    }
    private ListMessage(
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
            case 10: {
              if (!((mutable_bitField0_ & 0x00000001) != 0)) {
                value_ = new java.util.ArrayList<io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj>();
                mutable_bitField0_ |= 0x00000001;
              }
              value_.add(
                  input.readMessage(io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj.parser(), extensionRegistry));
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
          value_ = java.util.Collections.unmodifiableList(value_);
        }
        this.unknownFields = unknownFields.build();
        makeExtensionsImmutable();
      }
    }
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.edap.protobuf.test.message.v3.ListMessageOuterClass.internal_static_test_message_ListMessage_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.edap.protobuf.test.message.v3.ListMessageOuterClass.internal_static_test_message_ListMessage_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage.class, io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage.Builder.class);
    }

    public static final int VALUE_FIELD_NUMBER = 1;
    private java.util.List<io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj> value_;
    /**
     * <code>repeated .test.message.Proj value = 1;</code>
     */
    public java.util.List<io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj> getValueList() {
      return value_;
    }
    /**
     * <code>repeated .test.message.Proj value = 1;</code>
     */
    public java.util.List<? extends io.edap.protobuf.test.message.v3.OneMessageOuterClass.ProjOrBuilder> 
        getValueOrBuilderList() {
      return value_;
    }
    /**
     * <code>repeated .test.message.Proj value = 1;</code>
     */
    public int getValueCount() {
      return value_.size();
    }
    /**
     * <code>repeated .test.message.Proj value = 1;</code>
     */
    public io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj getValue(int index) {
      return value_.get(index);
    }
    /**
     * <code>repeated .test.message.Proj value = 1;</code>
     */
    public io.edap.protobuf.test.message.v3.OneMessageOuterClass.ProjOrBuilder getValueOrBuilder(
        int index) {
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
        output.writeMessage(1, value_.get(i));
      }
      unknownFields.writeTo(output);
    }

    @java.lang.Override
    public int getSerializedSize() {
      int size = memoizedSize;
      if (size != -1) return size;

      size = 0;
      for (int i = 0; i < value_.size(); i++) {
        size += com.google.protobuf.CodedOutputStream
          .computeMessageSize(1, value_.get(i));
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
      if (!(obj instanceof io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage)) {
        return super.equals(obj);
      }
      io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage other = (io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage) obj;

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

    public static io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage parseFrom(
        java.nio.ByteBuffer data)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage parseFrom(
        java.nio.ByteBuffer data,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage parseFrom(
        com.google.protobuf.ByteString data)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage parseFrom(
        com.google.protobuf.ByteString data,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage parseFrom(byte[] data)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage parseFrom(
        byte[] data,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage parseFrom(java.io.InputStream input)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessageV3
          .parseWithIOException(PARSER, input);
    }
    public static io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage parseFrom(
        java.io.InputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessageV3
          .parseWithIOException(PARSER, input, extensionRegistry);
    }
    public static io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage parseDelimitedFrom(java.io.InputStream input)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessageV3
          .parseDelimitedWithIOException(PARSER, input);
    }
    public static io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage parseDelimitedFrom(
        java.io.InputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessageV3
          .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
    }
    public static io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage parseFrom(
        com.google.protobuf.CodedInputStream input)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessageV3
          .parseWithIOException(PARSER, input);
    }
    public static io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage parseFrom(
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
    public static Builder newBuilder(io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage prototype) {
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
     * Protobuf type {@code test.message.ListMessage}
     */
    public static final class Builder extends
        com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
        // @@protoc_insertion_point(builder_implements:test.message.ListMessage)
        io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessageOrBuilder {
      public static final com.google.protobuf.Descriptors.Descriptor
          getDescriptor() {
        return io.edap.protobuf.test.message.v3.ListMessageOuterClass.internal_static_test_message_ListMessage_descriptor;
      }

      @java.lang.Override
      protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
          internalGetFieldAccessorTable() {
        return io.edap.protobuf.test.message.v3.ListMessageOuterClass.internal_static_test_message_ListMessage_fieldAccessorTable
            .ensureFieldAccessorsInitialized(
                io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage.class, io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage.Builder.class);
      }

      // Construct using io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage.newBuilder()
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
          getValueFieldBuilder();
        }
      }
      @java.lang.Override
      public Builder clear() {
        super.clear();
        if (valueBuilder_ == null) {
          value_ = java.util.Collections.emptyList();
          bitField0_ = (bitField0_ & ~0x00000001);
        } else {
          valueBuilder_.clear();
        }
        return this;
      }

      @java.lang.Override
      public com.google.protobuf.Descriptors.Descriptor
          getDescriptorForType() {
        return io.edap.protobuf.test.message.v3.ListMessageOuterClass.internal_static_test_message_ListMessage_descriptor;
      }

      @java.lang.Override
      public io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage getDefaultInstanceForType() {
        return io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage.getDefaultInstance();
      }

      @java.lang.Override
      public io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage build() {
        io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage result = buildPartial();
        if (!result.isInitialized()) {
          throw newUninitializedMessageException(result);
        }
        return result;
      }

      @java.lang.Override
      public io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage buildPartial() {
        io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage result = new io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage(this);
        int from_bitField0_ = bitField0_;
        if (valueBuilder_ == null) {
          if (((bitField0_ & 0x00000001) != 0)) {
            value_ = java.util.Collections.unmodifiableList(value_);
            bitField0_ = (bitField0_ & ~0x00000001);
          }
          result.value_ = value_;
        } else {
          result.value_ = valueBuilder_.build();
        }
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
        if (other instanceof io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage) {
          return mergeFrom((io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage)other);
        } else {
          super.mergeFrom(other);
          return this;
        }
      }

      public Builder mergeFrom(io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage other) {
        if (other == io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage.getDefaultInstance()) return this;
        if (valueBuilder_ == null) {
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
        } else {
          if (!other.value_.isEmpty()) {
            if (valueBuilder_.isEmpty()) {
              valueBuilder_.dispose();
              valueBuilder_ = null;
              value_ = other.value_;
              bitField0_ = (bitField0_ & ~0x00000001);
              valueBuilder_ = 
                com.google.protobuf.GeneratedMessageV3.alwaysUseFieldBuilders ?
                   getValueFieldBuilder() : null;
            } else {
              valueBuilder_.addAllMessages(other.value_);
            }
          }
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
        io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage parsedMessage = null;
        try {
          parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
          parsedMessage = (io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage) e.getUnfinishedMessage();
          throw e.unwrapIOException();
        } finally {
          if (parsedMessage != null) {
            mergeFrom(parsedMessage);
          }
        }
        return this;
      }
      private int bitField0_;

      private java.util.List<io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj> value_ =
        java.util.Collections.emptyList();
      private void ensureValueIsMutable() {
        if (!((bitField0_ & 0x00000001) != 0)) {
          value_ = new java.util.ArrayList<io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj>(value_);
          bitField0_ |= 0x00000001;
         }
      }

      private com.google.protobuf.RepeatedFieldBuilderV3<
          io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj, io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj.Builder, io.edap.protobuf.test.message.v3.OneMessageOuterClass.ProjOrBuilder> valueBuilder_;

      /**
       * <code>repeated .test.message.Proj value = 1;</code>
       */
      public java.util.List<io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj> getValueList() {
        if (valueBuilder_ == null) {
          return java.util.Collections.unmodifiableList(value_);
        } else {
          return valueBuilder_.getMessageList();
        }
      }
      /**
       * <code>repeated .test.message.Proj value = 1;</code>
       */
      public int getValueCount() {
        if (valueBuilder_ == null) {
          return value_.size();
        } else {
          return valueBuilder_.getCount();
        }
      }
      /**
       * <code>repeated .test.message.Proj value = 1;</code>
       */
      public io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj getValue(int index) {
        if (valueBuilder_ == null) {
          return value_.get(index);
        } else {
          return valueBuilder_.getMessage(index);
        }
      }
      /**
       * <code>repeated .test.message.Proj value = 1;</code>
       */
      public Builder setValue(
          int index, io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj value) {
        if (valueBuilder_ == null) {
          if (value == null) {
            throw new NullPointerException();
          }
          ensureValueIsMutable();
          value_.set(index, value);
          onChanged();
        } else {
          valueBuilder_.setMessage(index, value);
        }
        return this;
      }
      /**
       * <code>repeated .test.message.Proj value = 1;</code>
       */
      public Builder setValue(
          int index, io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj.Builder builderForValue) {
        if (valueBuilder_ == null) {
          ensureValueIsMutable();
          value_.set(index, builderForValue.build());
          onChanged();
        } else {
          valueBuilder_.setMessage(index, builderForValue.build());
        }
        return this;
      }
      /**
       * <code>repeated .test.message.Proj value = 1;</code>
       */
      public Builder addValue(io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj value) {
        if (valueBuilder_ == null) {
          if (value == null) {
            throw new NullPointerException();
          }
          ensureValueIsMutable();
          value_.add(value);
          onChanged();
        } else {
          valueBuilder_.addMessage(value);
        }
        return this;
      }
      /**
       * <code>repeated .test.message.Proj value = 1;</code>
       */
      public Builder addValue(
          int index, io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj value) {
        if (valueBuilder_ == null) {
          if (value == null) {
            throw new NullPointerException();
          }
          ensureValueIsMutable();
          value_.add(index, value);
          onChanged();
        } else {
          valueBuilder_.addMessage(index, value);
        }
        return this;
      }
      /**
       * <code>repeated .test.message.Proj value = 1;</code>
       */
      public Builder addValue(
          io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj.Builder builderForValue) {
        if (valueBuilder_ == null) {
          ensureValueIsMutable();
          value_.add(builderForValue.build());
          onChanged();
        } else {
          valueBuilder_.addMessage(builderForValue.build());
        }
        return this;
      }
      /**
       * <code>repeated .test.message.Proj value = 1;</code>
       */
      public Builder addValue(
          int index, io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj.Builder builderForValue) {
        if (valueBuilder_ == null) {
          ensureValueIsMutable();
          value_.add(index, builderForValue.build());
          onChanged();
        } else {
          valueBuilder_.addMessage(index, builderForValue.build());
        }
        return this;
      }
      /**
       * <code>repeated .test.message.Proj value = 1;</code>
       */
      public Builder addAllValue(
          java.lang.Iterable<? extends io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj> values) {
        if (valueBuilder_ == null) {
          ensureValueIsMutable();
          com.google.protobuf.AbstractMessageLite.Builder.addAll(
              values, value_);
          onChanged();
        } else {
          valueBuilder_.addAllMessages(values);
        }
        return this;
      }
      /**
       * <code>repeated .test.message.Proj value = 1;</code>
       */
      public Builder clearValue() {
        if (valueBuilder_ == null) {
          value_ = java.util.Collections.emptyList();
          bitField0_ = (bitField0_ & ~0x00000001);
          onChanged();
        } else {
          valueBuilder_.clear();
        }
        return this;
      }
      /**
       * <code>repeated .test.message.Proj value = 1;</code>
       */
      public Builder removeValue(int index) {
        if (valueBuilder_ == null) {
          ensureValueIsMutable();
          value_.remove(index);
          onChanged();
        } else {
          valueBuilder_.remove(index);
        }
        return this;
      }
      /**
       * <code>repeated .test.message.Proj value = 1;</code>
       */
      public io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj.Builder getValueBuilder(
          int index) {
        return getValueFieldBuilder().getBuilder(index);
      }
      /**
       * <code>repeated .test.message.Proj value = 1;</code>
       */
      public io.edap.protobuf.test.message.v3.OneMessageOuterClass.ProjOrBuilder getValueOrBuilder(
          int index) {
        if (valueBuilder_ == null) {
          return value_.get(index);  } else {
          return valueBuilder_.getMessageOrBuilder(index);
        }
      }
      /**
       * <code>repeated .test.message.Proj value = 1;</code>
       */
      public java.util.List<? extends io.edap.protobuf.test.message.v3.OneMessageOuterClass.ProjOrBuilder> 
           getValueOrBuilderList() {
        if (valueBuilder_ != null) {
          return valueBuilder_.getMessageOrBuilderList();
        } else {
          return java.util.Collections.unmodifiableList(value_);
        }
      }
      /**
       * <code>repeated .test.message.Proj value = 1;</code>
       */
      public io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj.Builder addValueBuilder() {
        return getValueFieldBuilder().addBuilder(
            io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj.getDefaultInstance());
      }
      /**
       * <code>repeated .test.message.Proj value = 1;</code>
       */
      public io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj.Builder addValueBuilder(
          int index) {
        return getValueFieldBuilder().addBuilder(
            index, io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj.getDefaultInstance());
      }
      /**
       * <code>repeated .test.message.Proj value = 1;</code>
       */
      public java.util.List<io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj.Builder> 
           getValueBuilderList() {
        return getValueFieldBuilder().getBuilderList();
      }
      private com.google.protobuf.RepeatedFieldBuilderV3<
          io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj, io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj.Builder, io.edap.protobuf.test.message.v3.OneMessageOuterClass.ProjOrBuilder> 
          getValueFieldBuilder() {
        if (valueBuilder_ == null) {
          valueBuilder_ = new com.google.protobuf.RepeatedFieldBuilderV3<
              io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj, io.edap.protobuf.test.message.v3.OneMessageOuterClass.Proj.Builder, io.edap.protobuf.test.message.v3.OneMessageOuterClass.ProjOrBuilder>(
                  value_,
                  ((bitField0_ & 0x00000001) != 0),
                  getParentForChildren(),
                  isClean());
          value_ = null;
        }
        return valueBuilder_;
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


      // @@protoc_insertion_point(builder_scope:test.message.ListMessage)
    }

    // @@protoc_insertion_point(class_scope:test.message.ListMessage)
    private static final io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage DEFAULT_INSTANCE;
    static {
      DEFAULT_INSTANCE = new io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage();
    }

    public static io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage getDefaultInstance() {
      return DEFAULT_INSTANCE;
    }

    private static final com.google.protobuf.Parser<ListMessage>
        PARSER = new com.google.protobuf.AbstractParser<ListMessage>() {
      @java.lang.Override
      public ListMessage parsePartialFrom(
          com.google.protobuf.CodedInputStream input,
          com.google.protobuf.ExtensionRegistryLite extensionRegistry)
          throws com.google.protobuf.InvalidProtocolBufferException {
        return new ListMessage(input, extensionRegistry);
      }
    };

    public static com.google.protobuf.Parser<ListMessage> parser() {
      return PARSER;
    }

    @java.lang.Override
    public com.google.protobuf.Parser<ListMessage> getParserForType() {
      return PARSER;
    }

    @java.lang.Override
    public io.edap.protobuf.test.message.v3.ListMessageOuterClass.ListMessage getDefaultInstanceForType() {
      return DEFAULT_INSTANCE;
    }

  }

  private static final com.google.protobuf.Descriptors.Descriptor
    internal_static_test_message_ListMessage_descriptor;
  private static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_test_message_ListMessage_fieldAccessorTable;

  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n\022list_message.proto\022\014test.message\032\021one_" +
      "message.proto\"0\n\013ListMessage\022!\n\005value\030\001 " +
      "\003(\0132\022.test.message.ProjB\"\n io.edap.proto" +
      "buf.test.message.v3b\006proto3"
    };
    descriptor = com.google.protobuf.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new com.google.protobuf.Descriptors.FileDescriptor[] {
          io.edap.protobuf.test.message.v3.OneMessageOuterClass.getDescriptor(),
        });
    internal_static_test_message_ListMessage_descriptor =
      getDescriptor().getMessageTypes().get(0);
    internal_static_test_message_ListMessage_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_test_message_ListMessage_descriptor,
        new java.lang.String[] { "Value", });
    io.edap.protobuf.test.message.v3.OneMessageOuterClass.getDescriptor();
  }

  // @@protoc_insertion_point(outer_class_scope)
}
