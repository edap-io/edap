/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.json.model;

import io.edap.json.enums.CommentItemType;

import java.util.Arrays;
import java.util.List;

public class CommentItem {
    private CommentItemType type;
    private List<String> comments;

    public CommentItemType getType() {
        return type;
    }

    public void setType(CommentItemType type) {
        this.type = type;
    }

    public List<String> getComments() {
        return comments;
    }

    public void setComments(List<String> comments) {
        this.comments = comments;
    }

    public static CommentItem emptyRow() {
        CommentItem item = new CommentItem();
        item.setType(CommentItemType.EMPTY_ROW);
        return item;
    }

    public static CommentItem singleLineComment(String comment) {
        CommentItem item = new CommentItem();
        item.setType(CommentItemType.SINGLE_COMMENT);
        item.setComments(Arrays.asList(comment));
        return item;
    }
}
