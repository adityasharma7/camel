/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.seda;

import org.apache.camel.impl.DefaultEndpoint;
import org.apache.camel.impl.DefaultExchange;
import org.apache.camel.ExchangeConverter;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Represents a SEDA endpoint using an internal {@link Queue}
 * object to process inbound exchanges.
 *
 * @version $Revision$
 */
public class SedaEndpoint<E> extends DefaultEndpoint<E> {
    private Queue queue;

    public SedaEndpoint(String uri, ExchangeConverter exchangeConverter) {
        this(uri, exchangeConverter, new ConcurrentLinkedQueue());
    }

    public SedaEndpoint(String uri, ExchangeConverter exchangeConverter, Queue queue) {
        super(uri, exchangeConverter);
        this.queue = queue;
    }

    public void send(E exchange) {
        queue.add(exchange);
    }

    public E createExchange() {
        return (E) new DefaultExchange();
    }

    public Queue getQueue() {
        return queue;
    }
}
