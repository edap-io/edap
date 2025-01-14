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

package io.edap.json.test.model;

import java.util.List;

public class DemoPojo {

    private String name;

    private int age;

    private boolean old;

    private double balance;

    private long integral;

    private List<DemoPojo> children;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
    }

    public boolean isOld() {
        return old;
    }

    public void setOld(boolean old) {
        this.old = old;
    }

    public double getBalance() {
        return balance;
    }

    public void setBalance(double balance) {
        this.balance = balance;
    }

    public long getIntegral() {
        return integral;
    }

    public void setIntegral(long integral) {
        this.integral = integral;
    }

    public List<DemoPojo> getChildren() {
        return children;
    }

    public void setChildren(List<DemoPojo> children) {
        this.children = children;
    }
}
