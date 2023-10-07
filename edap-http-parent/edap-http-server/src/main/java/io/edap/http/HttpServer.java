/*
 * Copyright (c) 2019 louis.lu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.edap.http;

import io.edap.Decoder;
import io.edap.NioSession;
import io.edap.Server;

/**
 */
public class HttpServer extends Server {

    private static Decoder<HttpRequest, HttpNioSession> VALUE_DECODER = new HttpDecoder();

    public enum DecoderType {
        NORMAL,
        FAST
    }

    private DecoderType decoderType = DecoderType.NORMAL;

    public void setDecoderType(DecoderType decoderType) {
        this.decoderType = decoderType;
    }

    @Override
    public void init() {
        super.init();
        System.out.println("HttpDecoder's type: " + decoderType);
    }

    @Override
    public NioSession createNioSession() {
        HttpNioSession nioSession = new HttpNioSession();
        nioSession.setServer(this);
            nioSession.setDecoder(VALUE_DECODER);
        return nioSession;
    }
}
