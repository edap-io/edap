// Generated by the protocol buffer compiler.  DO NOT EDIT!
// NO CHECKED-IN PROTOBUF GENCODE
// source: list_enum.proto
// Protobuf Java Version: 4.29.3

package io.edap.protobuf.test.message.v3;

public final class ListEnumOuterClass {
  private ListEnumOuterClass() {}
  static {
    com.google.protobuf.RuntimeVersion.validateProtobufGencodeVersion(
      com.google.protobuf.RuntimeVersion.RuntimeDomain.PUBLIC,
      /* major= */ 4,
      /* minor= */ 29,
      /* patch= */ 3,
      /* suffix= */ "",
      ListEnumOuterClass.class.getName());
  }
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
    registerAllExtensions(
        (com.google.protobuf.ExtensionRegistryLite) registry);
  }
  public interface ListEnumOrBuilder extends
      // @@protoc_insertion_point(interface_extends:test.message.ListEnum)
      com.google.protobuf.MessageOrBuilder {

    /**
     * <code>repeated .test.message.Corpus corpus = 1;</code>
     * @return A list containing the corpus.
     */
    java.util.List<io.edap.protobuf.test.message.v3.OneEnumOuterClass.Corpus> getCorpusList();
    /**
     * <code>repeated .test.message.Corpus corpus = 1;</code>
     * @return The count of corpus.
     */
    int getCorpusCount();
    /**
     * <code>repeated .test.message.Corpus corpus = 1;</code>
     * @param index The index of the element to return.
     * @return The corpus at the given index.
     */
    io.edap.protobuf.test.message.v3.OneEnumOuterClass.Corpus getCorpus(int index);
    /**
     * <code>repeated .test.message.Corpus corpus = 1;</code>
     * @return A list containing the enum numeric values on the wire for corpus.
     */
    java.util.List<java.lang.Integer>
    getCorpusValueList();
    /**
     * <code>repeated .test.message.Corpus corpus = 1;</code>
     * @param index The index of the value to return.
     * @return The enum numeric value on the wire of corpus at the given index.
     */
    int getCorpusValue(int index);
  }
  /**
   * Protobuf type {@code test.message.ListEnum}
   */
  public static final class ListEnum extends
      com.google.protobuf.GeneratedMessage implements
      // @@protoc_insertion_point(message_implements:test.message.ListEnum)
      ListEnumOrBuilder {
  private static final long serialVersionUID = 0L;
    static {
      com.google.protobuf.RuntimeVersion.validateProtobufGencodeVersion(
        com.google.protobuf.RuntimeVersion.RuntimeDomain.PUBLIC,
        /* major= */ 4,
        /* minor= */ 29,
        /* patch= */ 3,
        /* suffix= */ "",
        ListEnum.class.getName());
    }
    // Use ListEnum.newBuilder() to construct.
    private ListEnum(com.google.protobuf.GeneratedMessage.Builder<?> builder) {
      super(builder);
    }
    private ListEnum() {
      corpus_ = emptyIntList();
    }

    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.edap.protobuf.test.message.v3.ListEnumOuterClass.internal_static_test_message_ListEnum_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessage.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.edap.protobuf.test.message.v3.ListEnumOuterClass.internal_static_test_message_ListEnum_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum.class, io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum.Builder.class);
    }

    public static final int CORPUS_FIELD_NUMBER = 1;
    @SuppressWarnings("serial")
    private com.google.protobuf.Internal.IntList corpus_;
    private static final com.google.protobuf.Internal.IntListAdapter.IntConverter<
        io.edap.protobuf.test.message.v3.OneEnumOuterClass.Corpus> corpus_converter_ =
            new com.google.protobuf.Internal.IntListAdapter.IntConverter<
                io.edap.protobuf.test.message.v3.OneEnumOuterClass.Corpus>() {
              public io.edap.protobuf.test.message.v3.OneEnumOuterClass.Corpus convert(int from) {
                io.edap.protobuf.test.message.v3.OneEnumOuterClass.Corpus result = io.edap.protobuf.test.message.v3.OneEnumOuterClass.Corpus.forNumber(from);
                return result == null ? io.edap.protobuf.test.message.v3.OneEnumOuterClass.Corpus.UNRECOGNIZED : result;
              }
            };
    /**
     * <code>repeated .test.message.Corpus corpus = 1;</code>
     * @return A list containing the corpus.
     */
    @java.lang.Override
    public java.util.List<io.edap.protobuf.test.message.v3.OneEnumOuterClass.Corpus> getCorpusList() {
      return new com.google.protobuf.Internal.IntListAdapter<
          io.edap.protobuf.test.message.v3.OneEnumOuterClass.Corpus>(corpus_, corpus_converter_);
    }
    /**
     * <code>repeated .test.message.Corpus corpus = 1;</code>
     * @return The count of corpus.
     */
    @java.lang.Override
    public int getCorpusCount() {
      return corpus_.size();
    }
    /**
     * <code>repeated .test.message.Corpus corpus = 1;</code>
     * @param index The index of the element to return.
     * @return The corpus at the given index.
     */
    @java.lang.Override
    public io.edap.protobuf.test.message.v3.OneEnumOuterClass.Corpus getCorpus(int index) {
      return corpus_converter_.convert(corpus_.getInt(index));
    }
    /**
     * <code>repeated .test.message.Corpus corpus = 1;</code>
     * @return A list containing the enum numeric values on the wire for corpus.
     */
    @java.lang.Override
    public java.util.List<java.lang.Integer>
    getCorpusValueList() {
      return corpus_;
    }
    /**
     * <code>repeated .test.message.Corpus corpus = 1;</code>
     * @param index The index of the value to return.
     * @return The enum numeric value on the wire of corpus at the given index.
     */
    @java.lang.Override
    public int getCorpusValue(int index) {
      return corpus_.getInt(index);
    }
    private int corpusMemoizedSerializedSize;

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
      if (getCorpusList().size() > 0) {
        output.writeUInt32NoTag(10);
        output.writeUInt32NoTag(corpusMemoizedSerializedSize);
      }
      for (int i = 0; i < corpus_.size(); i++) {
        output.writeEnumNoTag(corpus_.getInt(i));
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
        for (int i = 0; i < corpus_.size(); i++) {
          dataSize += com.google.protobuf.CodedOutputStream
            .computeEnumSizeNoTag(corpus_.getInt(i));
        }
        size += dataSize;
        if (!getCorpusList().isEmpty()) {  size += 1;
          size += com.google.protobuf.CodedOutputStream
            .computeUInt32SizeNoTag(dataSize);
        }corpusMemoizedSerializedSize = dataSize;
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
      if (!(obj instanceof io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum)) {
        return super.equals(obj);
      }
      io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum other = (io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum) obj;

      if (!corpus_.equals(other.corpus_)) return false;
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
      if (getCorpusCount() > 0) {
        hash = (37 * hash) + CORPUS_FIELD_NUMBER;
        hash = (53 * hash) + corpus_.hashCode();
      }
      hash = (29 * hash) + getUnknownFields().hashCode();
      memoizedHashCode = hash;
      return hash;
    }

    public static io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum parseFrom(
        java.nio.ByteBuffer data)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum parseFrom(
        java.nio.ByteBuffer data,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum parseFrom(
        com.google.protobuf.ByteString data)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum parseFrom(
        com.google.protobuf.ByteString data,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum parseFrom(byte[] data)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum parseFrom(
        byte[] data,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum parseFrom(java.io.InputStream input)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessage
          .parseWithIOException(PARSER, input);
    }
    public static io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum parseFrom(
        java.io.InputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessage
          .parseWithIOException(PARSER, input, extensionRegistry);
    }

    public static io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum parseDelimitedFrom(java.io.InputStream input)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessage
          .parseDelimitedWithIOException(PARSER, input);
    }

    public static io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum parseDelimitedFrom(
        java.io.InputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessage
          .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
    }
    public static io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum parseFrom(
        com.google.protobuf.CodedInputStream input)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessage
          .parseWithIOException(PARSER, input);
    }
    public static io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum parseFrom(
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
    public static Builder newBuilder(io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum prototype) {
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
     * Protobuf type {@code test.message.ListEnum}
     */
    public static final class Builder extends
        com.google.protobuf.GeneratedMessage.Builder<Builder> implements
        // @@protoc_insertion_point(builder_implements:test.message.ListEnum)
        io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnumOrBuilder {
      public static final com.google.protobuf.Descriptors.Descriptor
          getDescriptor() {
        return io.edap.protobuf.test.message.v3.ListEnumOuterClass.internal_static_test_message_ListEnum_descriptor;
      }

      @java.lang.Override
      protected com.google.protobuf.GeneratedMessage.FieldAccessorTable
          internalGetFieldAccessorTable() {
        return io.edap.protobuf.test.message.v3.ListEnumOuterClass.internal_static_test_message_ListEnum_fieldAccessorTable
            .ensureFieldAccessorsInitialized(
                io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum.class, io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum.Builder.class);
      }

      // Construct using io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum.newBuilder()
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
        corpus_ = emptyIntList();
        bitField0_ = (bitField0_ & ~0x00000001);
        return this;
      }

      @java.lang.Override
      public com.google.protobuf.Descriptors.Descriptor
          getDescriptorForType() {
        return io.edap.protobuf.test.message.v3.ListEnumOuterClass.internal_static_test_message_ListEnum_descriptor;
      }

      @java.lang.Override
      public io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum getDefaultInstanceForType() {
        return io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum.getDefaultInstance();
      }

      @java.lang.Override
      public io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum build() {
        io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum result = buildPartial();
        if (!result.isInitialized()) {
          throw newUninitializedMessageException(result);
        }
        return result;
      }

      @java.lang.Override
      public io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum buildPartial() {
        io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum result = new io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum(this);
        buildPartialRepeatedFields(result);
        if (bitField0_ != 0) { buildPartial0(result); }
        onBuilt();
        return result;
      }

      private void buildPartialRepeatedFields(io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum result) {
        if (((bitField0_ & 0x00000001) != 0)) {
          corpus_.makeImmutable();
          bitField0_ = (bitField0_ & ~0x00000001);
        }
        result.corpus_ = corpus_;
      }

      private void buildPartial0(io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum result) {
        int from_bitField0_ = bitField0_;
      }

      @java.lang.Override
      public Builder mergeFrom(com.google.protobuf.Message other) {
        if (other instanceof io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum) {
          return mergeFrom((io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum)other);
        } else {
          super.mergeFrom(other);
          return this;
        }
      }

      public Builder mergeFrom(io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum other) {
        if (other == io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum.getDefaultInstance()) return this;
        if (!other.corpus_.isEmpty()) {
          if (corpus_.isEmpty()) {
            corpus_ = other.corpus_;
            bitField0_ = (bitField0_ & ~0x00000001);
          } else {
            ensureCorpusIsMutable();
            corpus_.addAll(other.corpus_);
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
              case 8: {
                int tmpRaw = input.readEnum();
                ensureCorpusIsMutable();
                corpus_.addInt(tmpRaw);
                break;
              } // case 8
              case 10: {
                int length = input.readRawVarint32();
                int oldLimit = input.pushLimit(length);
                while(input.getBytesUntilLimit() > 0) {
                  int tmpRaw = input.readEnum();
                  ensureCorpusIsMutable();
                  corpus_.addInt(tmpRaw);
                }
                input.popLimit(oldLimit);
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

      private com.google.protobuf.Internal.IntList corpus_ =
        emptyIntList();
      private void ensureCorpusIsMutable() {
        if (!((bitField0_ & 0x00000001) != 0)) {
          corpus_ = makeMutableCopy(corpus_);
          bitField0_ |= 0x00000001;
        }
      }
      /**
       * <code>repeated .test.message.Corpus corpus = 1;</code>
       * @return A list containing the corpus.
       */
      public java.util.List<io.edap.protobuf.test.message.v3.OneEnumOuterClass.Corpus> getCorpusList() {
        return new com.google.protobuf.Internal.IntListAdapter<
            io.edap.protobuf.test.message.v3.OneEnumOuterClass.Corpus>(corpus_, corpus_converter_);
      }
      /**
       * <code>repeated .test.message.Corpus corpus = 1;</code>
       * @return The count of corpus.
       */
      public int getCorpusCount() {
        return corpus_.size();
      }
      /**
       * <code>repeated .test.message.Corpus corpus = 1;</code>
       * @param index The index of the element to return.
       * @return The corpus at the given index.
       */
      public io.edap.protobuf.test.message.v3.OneEnumOuterClass.Corpus getCorpus(int index) {
        return corpus_converter_.convert(corpus_.getInt(index));
      }
      /**
       * <code>repeated .test.message.Corpus corpus = 1;</code>
       * @param index The index to set the value at.
       * @param value The corpus to set.
       * @return This builder for chaining.
       */
      public Builder setCorpus(
          int index, io.edap.protobuf.test.message.v3.OneEnumOuterClass.Corpus value) {
        if (value == null) {
          throw new NullPointerException();
        }
        ensureCorpusIsMutable();
        corpus_.setInt(index, value.getNumber());
        onChanged();
        return this;
      }
      /**
       * <code>repeated .test.message.Corpus corpus = 1;</code>
       * @param value The corpus to add.
       * @return This builder for chaining.
       */
      public Builder addCorpus(io.edap.protobuf.test.message.v3.OneEnumOuterClass.Corpus value) {
        if (value == null) {
          throw new NullPointerException();
        }
        ensureCorpusIsMutable();
        corpus_.addInt(value.getNumber());
        onChanged();
        return this;
      }
      /**
       * <code>repeated .test.message.Corpus corpus = 1;</code>
       * @param values The corpus to add.
       * @return This builder for chaining.
       */
      public Builder addAllCorpus(
          java.lang.Iterable<? extends io.edap.protobuf.test.message.v3.OneEnumOuterClass.Corpus> values) {
        ensureCorpusIsMutable();
        for (io.edap.protobuf.test.message.v3.OneEnumOuterClass.Corpus value : values) {
          corpus_.addInt(value.getNumber());
        }
        onChanged();
        return this;
      }
      /**
       * <code>repeated .test.message.Corpus corpus = 1;</code>
       * @return This builder for chaining.
       */
      public Builder clearCorpus() {
        corpus_ = emptyIntList();
        bitField0_ = (bitField0_ & ~0x00000001);
        onChanged();
        return this;
      }
      /**
       * <code>repeated .test.message.Corpus corpus = 1;</code>
       * @return A list containing the enum numeric values on the wire for corpus.
       */
      public java.util.List<java.lang.Integer>
      getCorpusValueList() {
        return java.util.Collections.unmodifiableList(corpus_);
      }
      /**
       * <code>repeated .test.message.Corpus corpus = 1;</code>
       * @param index The index of the value to return.
       * @return The enum numeric value on the wire of corpus at the given index.
       */
      public int getCorpusValue(int index) {
        return corpus_.getInt(index);
      }
      /**
       * <code>repeated .test.message.Corpus corpus = 1;</code>
       * @param index The index to set the value at.
       * @param value The enum numeric value on the wire for corpus to set.
       * @return This builder for chaining.
       */
      public Builder setCorpusValue(
          int index, int value) {
        ensureCorpusIsMutable();
        corpus_.setInt(index, value);
        onChanged();
        return this;
      }
      /**
       * <code>repeated .test.message.Corpus corpus = 1;</code>
       * @param value The enum numeric value on the wire for corpus to add.
       * @return This builder for chaining.
       */
      public Builder addCorpusValue(int value) {
        ensureCorpusIsMutable();
        corpus_.addInt(value);
        onChanged();
        return this;
      }
      /**
       * <code>repeated .test.message.Corpus corpus = 1;</code>
       * @param values The enum numeric values on the wire for corpus to add.
       * @return This builder for chaining.
       */
      public Builder addAllCorpusValue(
          java.lang.Iterable<java.lang.Integer> values) {
        ensureCorpusIsMutable();
        for (int value : values) {
          corpus_.addInt(value);
        }
        onChanged();
        return this;
      }

      // @@protoc_insertion_point(builder_scope:test.message.ListEnum)
    }

    // @@protoc_insertion_point(class_scope:test.message.ListEnum)
    private static final io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum DEFAULT_INSTANCE;
    static {
      DEFAULT_INSTANCE = new io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum();
    }

    public static io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum getDefaultInstance() {
      return DEFAULT_INSTANCE;
    }

    private static final com.google.protobuf.Parser<ListEnum>
        PARSER = new com.google.protobuf.AbstractParser<ListEnum>() {
      @java.lang.Override
      public ListEnum parsePartialFrom(
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

    public static com.google.protobuf.Parser<ListEnum> parser() {
      return PARSER;
    }

    @java.lang.Override
    public com.google.protobuf.Parser<ListEnum> getParserForType() {
      return PARSER;
    }

    @java.lang.Override
    public io.edap.protobuf.test.message.v3.ListEnumOuterClass.ListEnum getDefaultInstanceForType() {
      return DEFAULT_INSTANCE;
    }

  }

  private static final com.google.protobuf.Descriptors.Descriptor
    internal_static_test_message_ListEnum_descriptor;
  private static final 
    com.google.protobuf.GeneratedMessage.FieldAccessorTable
      internal_static_test_message_ListEnum_fieldAccessorTable;

  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n\017list_enum.proto\022\014test.message\032\016one_enu" +
      "m.proto\"0\n\010ListEnum\022$\n\006corpus\030\001 \003(\0162\024.te" +
      "st.message.CorpusB\"\n io.edap.protobuf.te" +
      "st.message.v3b\006proto3"
    };
    descriptor = com.google.protobuf.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new com.google.protobuf.Descriptors.FileDescriptor[] {
          io.edap.protobuf.test.message.v3.OneEnumOuterClass.getDescriptor(),
        });
    internal_static_test_message_ListEnum_descriptor =
      getDescriptor().getMessageTypes().get(0);
    internal_static_test_message_ListEnum_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessage.FieldAccessorTable(
        internal_static_test_message_ListEnum_descriptor,
        new java.lang.String[] { "Corpus", });
    descriptor.resolveAllFeaturesImmutable();
    io.edap.protobuf.test.message.v3.OneEnumOuterClass.getDescriptor();
  }

  // @@protoc_insertion_point(outer_class_scope)
}
