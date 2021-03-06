/*
 * Copyright (C) 2015 Red Bull Media House GmbH <http://www.redbullmediahouse.com> - all rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rbmhtechnology.example.ordermgnt.japi;

import akka.actor.ExtendedActorSystem;
import akka.serialization.*;

import fj.data.List;

import java.io.Serializable;
import java.util.ArrayList;

public class OrderSerializer extends JSerializer {
    private static class OrderFormat implements Serializable {
        public String id;
        public ArrayList<String> items;
        public boolean cancelled;
    }

    private ExtendedActorSystem system;

    public OrderSerializer(ExtendedActorSystem system) {
        this.system = system;
    }

    @Override
    public int identifier() {
        return 77355;
    }

    @Override
    public boolean includeManifest() {
        return true;
    }

    @Override
    public Object fromBinaryJava(byte[] bytes, Class<?> manifest) {
        OrderFormat orderFormat = SerializationExtension.get(system).deserialize(bytes, OrderFormat.class).get();

        return new Order(orderFormat.id, List.iterableList(orderFormat.items), orderFormat.cancelled);
    }

    @Override
    public byte[] toBinary(Object o) {
        Order order = (Order)o;
        OrderFormat orderFormat = new OrderFormat();

        orderFormat.id = order.getId();
        orderFormat.items = new ArrayList<>(order.getItems().toCollection());
        orderFormat.cancelled = order.isCancelled();

        return SerializationExtension.get(system).serialize(orderFormat).get();
    }
}
