// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: one_double.proto

package io.edap.protobuf.test.message.v3;

public final class OneDoubleOuterClass {
  private OneDoubleOuterClass() {}
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
    registerAllExtensions(
        (com.google.protobuf.ExtensionRegistryLite) registry);
  }
  public interface OneDoubleOrBuilder extends
      // @@protoc_insertion_point(interface_extends:test.message.OneDouble)
      com.google.protobuf.MessageOrBuilder {

    /**
     * <code>double d = 1;</code>
     * @return The d.
     */
    double getD();
  }
  /**
   * Protobuf type {@code test.message.OneDouble}
   */
  public  static final class OneDouble extends
      com.google.protobuf.GeneratedMessageV3 implements
      // @@protoc_insertion_point(message_implements:test.message.OneDouble)
      OneDoubleOrBuilder {
  private static final long serialVersionUID = 0L;
    // Use OneDouble.newBuilder() to construct.
    private OneDouble(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
      super(builder);
    }
    private OneDouble() {
    }

    @java.lang.Override
    @SuppressWarnings({"unused"})
    protected java.lang.Object newInstance(
        UnusedPrivateParameter unused) {
      return new OneDouble();
    }

    @java.lang.Override
    public final com.google.protobuf.UnknownFieldSet
    getUnknownFields() {
      return this.unknownFields;
    }
    private OneDouble(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      this();
      if (extensionRegistry == null) {
        throw new java.lang.NullPointerException();
      }
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

              d_ = input.readDouble();
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
        this.unknownFields = unknownFields.build();
        makeExtensionsImmutable();
      }
    }
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.edap.protobuf.test.message.v3.OneDoubleOuterClass.internal_static_test_message_OneDouble_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.edap.protobuf.test.message.v3.OneDoubleOuterClass.internal_static_test_message_OneDouble_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble.class, io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble.Builder.class);
    }

    public static final int D_FIELD_NUMBER = 1;
    private double d_;
    /**
     * <code>double d = 1;</code>
     * @return The d.
     */
    public double getD() {
      return d_;
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
      if (d_ != 0D) {
        output.writeDouble(1, d_);
      }
      unknownFields.writeTo(output);
    }

    @java.lang.Override
    public int getSerializedSize() {
      int size = memoizedSize;
      if (size != -1) return size;

      size = 0;
      if (d_ != 0D) {
        size += com.google.protobuf.CodedOutputStream
          .computeDoubleSize(1, d_);
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
      if (!(obj instanceof io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble)) {
        return super.equals(obj);
      }
      io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble other = (io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble) obj;

      if (java.lang.Double.doubleToLongBits(getD())
          != java.lang.Double.doubleToLongBits(
              other.getD())) return false;
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
      hash = (37 * hash) + D_FIELD_NUMBER;
      hash = (53 * hash) + com.google.protobuf.Internal.hashLong(
          java.lang.Double.doubleToLongBits(getD()));
      hash = (29 * hash) + unknownFields.hashCode();
      memoizedHashCode = hash;
      return hash;
    }

    public static io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble parseFrom(
        java.nio.ByteBuffer data)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble parseFrom(
        java.nio.ByteBuffer data,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble parseFrom(
        com.google.protobuf.ByteString data)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble parseFrom(
        com.google.protobuf.ByteString data,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble parseFrom(byte[] data)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble parseFrom(
        byte[] data,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble parseFrom(java.io.InputStream input)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessageV3
          .parseWithIOException(PARSER, input);
    }
    public static io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble parseFrom(
        java.io.InputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessageV3
          .parseWithIOException(PARSER, input, extensionRegistry);
    }
    public static io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble parseDelimitedFrom(java.io.InputStream input)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessageV3
          .parseDelimitedWithIOException(PARSER, input);
    }
    public static io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble parseDelimitedFrom(
        java.io.InputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessageV3
          .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
    }
    public static io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble parseFrom(
        com.google.protobuf.CodedInputStream input)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessageV3
          .parseWithIOException(PARSER, input);
    }
    public static io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble parseFrom(
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
    public static Builder newBuilder(io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble prototype) {
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
     * Protobuf type {@code test.message.OneDouble}
     */
    public static final class Builder extends
        com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
        // @@protoc_insertion_point(builder_implements:test.message.OneDouble)
        io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDoubleOrBuilder {
      public static final com.google.protobuf.Descriptors.Descriptor
          getDescriptor() {
        return io.edap.protobuf.test.message.v3.OneDoubleOuterClass.internal_static_test_message_OneDouble_descriptor;
      }

      @java.lang.Override
      protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
          internalGetFieldAccessorTable() {
        return io.edap.protobuf.test.message.v3.OneDoubleOuterClass.internal_static_test_message_OneDouble_fieldAccessorTable
            .ensureFieldAccessorsInitialized(
                io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble.class, io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble.Builder.class);
      }

      // Construct using io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble.newBuilder()
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
        d_ = 0D;

        return this;
      }

      @java.lang.Override
      public com.google.protobuf.Descriptors.Descriptor
          getDescriptorForType() {
        return io.edap.protobuf.test.message.v3.OneDoubleOuterClass.internal_static_test_message_OneDouble_descriptor;
      }

      @java.lang.Override
      public io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble getDefaultInstanceForType() {
        return io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble.getDefaultInstance();
      }

      @java.lang.Override
      public io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble build() {
        io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble result = buildPartial();
        if (!result.isInitialized()) {
          throw newUninitializedMessageException(result);
        }
        return result;
      }

      @java.lang.Override
      public io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble buildPartial() {
        io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble result = new io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble(this);
        result.d_ = d_;
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
        if (other instanceof io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble) {
          return mergeFrom((io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble)other);
        } else {
          super.mergeFrom(other);
          return this;
        }
      }

      public Builder mergeFrom(io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble other) {
        if (other == io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble.getDefaultInstance()) return this;
        if (other.getD() != 0D) {
          setD(other.getD());
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
        io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble parsedMessage = null;
        try {
          parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
          parsedMessage = (io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble) e.getUnfinishedMessage();
          throw e.unwrapIOException();
        } finally {
          if (parsedMessage != null) {
            mergeFrom(parsedMessage);
          }
        }
        return this;
      }

      private double d_ ;
      /**
       * <code>double d = 1;</code>
       * @return The d.
       */
      public double getD() {
        return d_;
      }
      /**
       * <code>double d = 1;</code>
       * @param value The d to set.
       * @return This builder for chaining.
       */
      public Builder setD(double value) {
        
        d_ = value;
        onChanged();
        return this;
      }
      /**
       * <code>double d = 1;</code>
       * @return This builder for chaining.
       */
      public Builder clearD() {
        
        d_ = 0D;
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


      // @@protoc_insertion_point(builder_scope:test.message.OneDouble)
    }

    // @@protoc_insertion_point(class_scope:test.message.OneDouble)
    private static final io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble DEFAULT_INSTANCE;
    static {
      DEFAULT_INSTANCE = new io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble();
    }

    public static io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble getDefaultInstance() {
      return DEFAULT_INSTANCE;
    }

    private static final com.google.protobuf.Parser<OneDouble>
        PARSER = new com.google.protobuf.AbstractParser<OneDouble>() {
      @java.lang.Override
      public OneDouble parsePartialFrom(
          com.google.protobuf.CodedInputStream input,
          com.google.protobuf.ExtensionRegistryLite extensionRegistry)
          throws com.google.protobuf.InvalidProtocolBufferException {
        return new OneDouble(input, extensionRegistry);
      }
    };

    public static com.google.protobuf.Parser<OneDouble> parser() {
      return PARSER;
    }

    @java.lang.Override
    public com.google.protobuf.Parser<OneDouble> getParserForType() {
      return PARSER;
    }

    @java.lang.Override
    public io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble getDefaultInstanceForType() {
      return DEFAULT_INSTANCE;
    }

  }

  private static final com.google.protobuf.Descriptors.Descriptor
    internal_static_test_message_OneDouble_descriptor;
  private static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_test_message_OneDouble_fieldAccessorTable;

  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n\020one_double.proto\022\014test.message\"\026\n\tOneD" +
      "ouble\022\t\n\001d\030\001 \001(\001B\"\n io.edap.protobuf.tes" +
      "t.message.v3b\006proto3"
    };
    descriptor = com.google.protobuf.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new com.google.protobuf.Descriptors.FileDescriptor[] {
        });
    internal_static_test_message_OneDouble_descriptor =
      getDescriptor().getMessageTypes().get(0);
    internal_static_test_message_OneDouble_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_test_message_OneDouble_descriptor,
        new java.lang.String[] { "D", });
  }

  // @@protoc_insertion_point(outer_class_scope)
}
