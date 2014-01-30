//
//  PackageHandler.java
//  AdjustIo
//
//  Created by Christian Wellenbrock on 2013-06-25.
//  Copyright (c) 2013 adeven. All rights reserved.
//  See the file MIT-LICENSE for copying permission.
//

package com.adeven.adjustio;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OptionalDataException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

// persistent
public class QueuePackageHandler extends HandlerThread implements PackageHandler {
    private static final String PACKAGE_QUEUE_FILENAME = "AdjustIoPackageQueue";

    private final InternalHandler       internalHandler;
    private       RequestHandler        requestHandler;
    private       List<ActivityPackage> packageQueue;
    private       AtomicBoolean         isSending;
    private       boolean               paused;
    private       Context               context;
    private       Logger				logger;

    public QueuePackageHandler() {
        super(Constants.LOGTAG, MIN_PRIORITY);
        setDaemon(true);
        start();
        this.internalHandler = new InternalHandler(getLooper(), this);
    }
    public void setContext(Context context) {
        this.context = context;
        this.logger = (Logger) AdjustIoFactory.getInstance(Logger.class);
        
        this.requestHandler = (RequestHandler) AdjustIoFactory.getInstance(RequestHandler.class);
        this.requestHandler.setPackageHandler(this);
        
        Message message = Message.obtain();
        message.arg1 = InternalHandler.INIT;
        internalHandler.sendMessage(message);
    }

    // add a package to the queue, trigger sending
    public void addPackage(ActivityPackage pack) {
        Message message = Message.obtain();
        message.arg1 = InternalHandler.ADD;
        message.obj = pack;
        internalHandler.sendMessage(message);
    }

    // try to send the oldest package
    public void sendFirstPackage() {
        Message message = Message.obtain();
        message.arg1 = InternalHandler.SEND_FIRST;
        internalHandler.sendMessage(message);
    }

    // remove oldest package and try to send the next one
    // (after success or possibly permanent failure)
    public void sendNextPackage() {
        Message message = Message.obtain();
        message.arg1 = InternalHandler.SEND_NEXT;
        internalHandler.sendMessage(message);
    }

    // close the package to retry in the future (after temporary failure)
    public void closeFirstPackage() {
        isSending.set(false);
    }

    // interrupt the sending loop after the current request has finished
    public void pauseSending() {
        paused = true;
    }

    // allow sending requests again
    public void resumeSending() {
        paused = false;
    }

    private static final class InternalHandler extends Handler {
        private static final int INIT       = 1;
        private static final int ADD        = 2;
        private static final int SEND_NEXT  = 3;
        private static final int SEND_FIRST = 4;

        private final WeakReference<QueuePackageHandler> packageHandlerReference;

        protected InternalHandler(Looper looper, QueuePackageHandler packageHandler) {
            super(looper);
            this.packageHandlerReference = new WeakReference<QueuePackageHandler>(packageHandler);
        }

        @Override
        public void handleMessage(Message message) {
            super.handleMessage(message);

            QueuePackageHandler packageHandler = packageHandlerReference.get();
            if (null == packageHandler) {
                return;
            }

            switch (message.arg1) {
                case INIT:
                    packageHandler.initInternal();
                    break;
                case ADD:
                    ActivityPackage activityPackage = (ActivityPackage) message.obj;
                    packageHandler.addInternal(activityPackage);
                    break;
                case SEND_FIRST:
                    packageHandler.sendFirstInternal();
                    break;
                case SEND_NEXT:
                    packageHandler.sendNextInternal();
                    break;
            }
        }
    }

    // internal methods run in dedicated queue thread

    private void initInternal() {
        isSending = new AtomicBoolean();

        readPackageQueue();
    }

    private void addInternal(ActivityPackage newPackage) {
        packageQueue.add(newPackage);
        logger.debug(String.format(Locale.US, "Added package %d (%s)", packageQueue.size(), newPackage));
        logger.verbose(newPackage.getExtendedString());

        writePackageQueue();
    }

    private void sendFirstInternal() {
        if (packageQueue.isEmpty()) {
            return;
        }

        if (paused) {
            logger.debug("Package handler is paused");
            return;
        }
        if (isSending.getAndSet(true)) {
            logger.verbose("Package handler is already sending");
            return;
        }

        ActivityPackage firstPackage = packageQueue.get(0);
        requestHandler.sendPackage(firstPackage);
    }

    private void sendNextInternal() {
        packageQueue.remove(0);
        writePackageQueue();
        isSending.set(false);
        sendFirstInternal();
    }

    private void readPackageQueue() {
        try {
            FileInputStream inputStream = context.openFileInput(PACKAGE_QUEUE_FILENAME);
            BufferedInputStream bufferedStream = new BufferedInputStream(inputStream);
            ObjectInputStream objectStream = new ObjectInputStream(bufferedStream);

            try {
                Object object = objectStream.readObject();
                @SuppressWarnings("unchecked")
                List<ActivityPackage> packageQueue = (List<ActivityPackage>) object;
                logger.debug(String.format(Locale.US, "Package handler read %d packages", packageQueue.size()));
                this.packageQueue = packageQueue;
                return;
            } catch (ClassNotFoundException e) {
                logger.error("Failed to find package queue class");
            } catch (OptionalDataException e) {
                /* no-op */
            } catch (IOException e) {
                logger.error("Failed to read package queue object");
            } catch (ClassCastException e) {
                logger.error("Failed to cast package queue object");
            } finally {
                objectStream.close();
            }
        } catch (FileNotFoundException e) {
            logger.verbose("Package queue file not found");
        } catch (Exception e) {
            logger.error("Failed to read package queue file");
        }

        // start with a fresh package queue in case of any exception
        packageQueue = new ArrayList<ActivityPackage>();
    }

    private void writePackageQueue() {
        try {
            FileOutputStream outputStream = context.openFileOutput(PACKAGE_QUEUE_FILENAME, Context.MODE_PRIVATE);
            BufferedOutputStream bufferedStream = new BufferedOutputStream(outputStream);
            ObjectOutputStream objectStream = new ObjectOutputStream(bufferedStream);

            try {
                objectStream.writeObject(packageQueue);
                logger.debug(String.format(Locale.US, "Package handler wrote %d packages", packageQueue.size()));
            } catch (NotSerializableException e) {
                logger.error("Failed to serialize packages");
            } finally {
                objectStream.close();
            }
        } catch (Exception e) {
            logger.error(String.format("Failed to write packages (%s)", e.getLocalizedMessage()));
            e.printStackTrace();
        }
    }
}
