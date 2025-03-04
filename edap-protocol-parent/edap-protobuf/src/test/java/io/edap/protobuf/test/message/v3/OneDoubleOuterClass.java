// Generated by the protocol buffer compiler.  DO NOT EDIT!
// NO CHECKED-IN PROTOBUF GENCODE
// source: one_double.proto
// Protobuf Java Version: 4.29.3

package io.edap.protobuf.test.message.v3;

public final class OneDoubleOuterClass {
  private OneDoubleOuterClass() {}
  static {
    com.google.protobuf.RuntimeVersion.validateProtobufGencodeVersion(
      com.google.protobuf.RuntimeVersion.RuntimeDomain.PUBLIC,
      /* major= */ 4,
      /* minor= */ 29,
      /* patch= */ 3,
      /* suffix= */ "",
      OneDoubleOuterClass.class.getName());
  }
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
  public static final class OneDouble extends
      com.google.protobuf.GeneratedMessage implements
      // @@protoc_insertion_point(message_implements:test.message.OneDouble)
      OneDoubleOrBuilder {
  private static final long serialVersionUID = 0L;
    static {
      com.google.protobuf.RuntimeVersion.validateProtobufGencodeVersion(
        com.google.protobuf.RuntimeVersion.RuntimeDomain.PUBLIC,
        /* major= */ 4,
        /* minor= */ 29,
        /* patch= */ 3,
        /* suffix= */ "",
        OneDouble.class.getName());
    }
    // Use OneDouble.newBuilder() to construct.
    private OneDouble(com.google.protobuf.GeneratedMessage.Builder<?> builder) {
      super(builder);
    }
    private OneDouble() {
    }

    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.edap.protobuf.test.message.v3.OneDoubleOuterClass.internal_static_test_message_OneDouble_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessage.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.edap.protobuf.test.message.v3.OneDoubleOuterClass.internal_static_test_message_OneDouble_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble.class, io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble.Builder.class);
    }

    public static final int D_FIELD_NUMBER = 1;
    private double d_ = 0D;
    /**
     * <code>double d = 1;</code>
     * @return The d.
     */
    @java.lang.Override
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
      if (java.lang.Double.doubleToRawLongBits(d_) != 0) {
        output.writeDouble(1, d_);
      }
      getUnknownFields().writeTo(output);
    }

    @java.lang.Override
    public int getSerializedSize() {
      int size = memoizedSize;
      if (size != -1) return size;

      size = 0;
      if (java.lang.Double.doubleToRawLongBits(d_) != 0) {
        size += com.google.protobuf.CodedOutputStream
          .computeDoubleSize(1, d_);
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
      if (!(obj instanceof io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble)) {
        return super.equals(obj);
      }
      io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble other = (io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble) obj;

      if (java.lang.Double.doubleToLongBits(getD())
          != java.lang.Double.doubleToLongBits(
              other.getD())) return false;
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
      hash = (37 * hash) + D_FIELD_NUMBER;
      hash = (53 * hash) + com.google.protobuf.Internal.hashLong(
          java.lang.Double.doubleToLongBits(getD()));
      hash = (29 * hash) + getUnknownFields().hashCode();
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
      return com.google.protobuf.GeneratedMessage
          .parseWithIOException(PARSER, input);
    }
    public static io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble parseFrom(
        java.io.InputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessage
          .parseWithIOException(PARSER, input, extensionRegistry);
    }

    public static io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble parseDelimitedFrom(java.io.InputStream input)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessage
          .parseDelimitedWithIOException(PARSER, input);
    }

    public static io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble parseDelimitedFrom(
        java.io.InputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessage
          .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
    }
    public static io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble parseFrom(
        com.google.protobuf.CodedInputStream input)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessage
          .parseWithIOException(PARSER, input);
    }
    public static io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble parseFrom(
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
        com.google.protobuf.GeneratedMessage.BuilderParent parent) {
      Builder builder = new Builder(parent);
      return builder;
    }
    /**
     * Protobuf type {@code test.message.OneDouble}
     */
    public static final class Builder extends
        com.google.protobuf.GeneratedMessage.Builder<Builder> implements
        // @@protoc_insertion_point(builder_implements:test.message.OneDouble)
        io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDoubleOrBuilder {
      public static final com.google.protobuf.Descriptors.Descriptor
          getDescriptor() {
        return io.edap.protobuf.test.message.v3.OneDoubleOuterClass.internal_static_test_message_OneDouble_descriptor;
      }

      @java.lang.Override
      protected com.google.protobuf.GeneratedMessage.FieldAccessorTable
          internalGetFieldAccessorTable() {
        return io.edap.protobuf.test.message.v3.OneDoubleOuterClass.internal_static_test_message_OneDouble_fieldAccessorTable
            .ensureFieldAccessorsInitialized(
                io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble.class, io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble.Builder.class);
      }

      // Construct using io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble.newBuilder()
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
        if (bitField0_ != 0) { buildPartial0(result); }
        onBuilt();
        return result;
      }

      private void buildPartial0(io.edap.protobuf.test.message.v3.OneDoubleOuterClass.OneDouble result) {
        int from_bitField0_ = bitField0_;
        if (((from_bitField0_ & 0x00000001) != 0)) {
          result.d_ = d_;
        }
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
              case 9: {
                d_ = input.readDouble();
                bitField0_ |= 0x00000001;
                break;
              } // case 9
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

      private double d_ ;
      /**
       * <code>double d = 1;</code>
       * @return The d.
       */
      @java.lang.Override
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
        bitField0_ |= 0x00000001;
        onChanged();
        return this;
      }
      /**
       * <code>double d = 1;</code>
       * @return This builder for chaining.
       */
      public Builder clearD() {
        bitField0_ = (bitField0_ & ~0x00000001);
        d_ = 0D;
        onChanged();
        return this;
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
    com.google.protobuf.GeneratedMessage.FieldAccessorTable
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
      com.google.protobuf.GeneratedMessage.FieldAccessorTable(
        internal_static_test_message_OneDouble_descriptor,
        new java.lang.String[] { "D", });
    descriptor.resolveAllFeaturesImmutable();
  }

  // @@protoc_insertion_point(outer_class_scope)
}
