/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.coyote.http11.upgrade;

import java.io.IOException;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;

import org.apache.tomcat.util.res.StringManager;

public abstract class AbstractServletOutputStream extends ServletOutputStream {

    protected static final StringManager sm =
            StringManager.getManager(Constants.Package);

    private final Object fireListenerLock = new Object();
    private final Object writeLock = new Object();

    // Start in blocking-mode
    private volatile WriteListener listener = null;
    private volatile boolean fireListener = false;
    private volatile byte[] buffer;

    @Override
    public final boolean canWrite() {
        if (listener == null) {
            throw new IllegalStateException(
                    sm.getString("upgrade.sos.canWrite.is"));
        }

        // Make sure canWrite() and onWritePossible() have a consistent view of
        // buffer and fireListener when determining if the listener should fire
        synchronized (fireListenerLock) {
            boolean result = (buffer == null);
            fireListener = !result;
            return result;
        }
    }

    @Override
    public final void setWriteListener(WriteListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException(
                    sm.getString("upgrade.sos.writeListener.null"));
        }
        this.listener = listener;
    }

    @Override
    public void write(int b) throws IOException {
        preWriteChecks();

        writeInternal(new byte[] { (byte) b }, 0, 1);
    }


    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        preWriteChecks();

        writeInternal(b, off, len);
    }


    @Override
    public void close() throws IOException {
        doClose();
    }

    private void preWriteChecks() {
        if (buffer != null) {
            throw new IllegalStateException(
                    sm.getString("upgrade.sis.write.ise"));
        }
    }


    private void writeInternal(byte[] b, int off, int len) throws IOException {
        synchronized (writeLock) {
            if (listener == null) {
                // Simple case - blocking IO
                doWrite(true, b, off, len);
            } else {
                // Non-blocking IO
                // If the non-blocking read does not complete, doWrite() will add
                // the socket back into the poller. The poller way trigger a new
                // write event before this method has finished updating buffer. This
                // sync makes sure that buffer is updated before the next write
                // executes.
                int written = doWrite(false, b, off, len);
                if (written < len) {
                    // TODO: - Reuse the buffer
                    //       - Only reallocate if it gets too big (>8k?)
                    buffer = new byte[len - written];
                    System.arraycopy(b, off + written, buffer, 0, len - written);
                } else {
                    buffer = null;
                }
            }
        }
    }


    protected final void onWritePossible() {
        try {
            writeInternal(buffer, 0, buffer.length);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        // Make sure canWrite() and onWritePossible() have a consistent view of
        // buffer and fireListener when determining if the listener should fire
        synchronized (fireListenerLock) {
            if (buffer == null && fireListener) {
                fireListener = false;
                listener.onWritePossible();
            }
        }
    }

    protected abstract int doWrite(boolean block, byte[] b, int off, int len)
            throws IOException;

    protected abstract void doFlush() throws IOException;

    protected abstract void doClose() throws IOException;
}
