/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.log.queue;

import com.lmax.disruptor.EventHandler;
import io.edap.log.LogQueue;
import io.edap.log.helps.ByteArrayBuilder;
import io.edap.log.io.BaseLogOutputStream;

import java.io.IOException;

import static io.edap.log.helpers.Util.printError;

/**
 * 异步写日志数据的队列
 */
public interface LogDataQueue extends LogQueue<ByteArrayBuilder, LogDataQueue.WriteEvent> {

    void setEventHandler(EventHandler<WriteEvent> handler);

    static void translate(WriteEvent event, long sequence, ByteArrayBuilder builder) {
        event.setOutputStream(builder.getOutputStream());
//        try {
//            builder.writeTo(builder.getOutputStream());
//        } catch (IOException e) {
//            printError("write error", e);
//        }
    }

    class WriteEvent {
        private BaseLogOutputStream outputStream;

        public BaseLogOutputStream getOutputStream() {
            return outputStream;
        }

        public void setOutputStream(BaseLogOutputStream outputStream) {
            this.outputStream = outputStream;
        }
    }

}
