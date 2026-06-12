package com.android.internal.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/**
 * Hidden API stub for IAppOpsService
 */
public interface IAppOpsService extends IInterface {

    void setMode(int code, int uid, String packageName, int mode) throws RemoteException;

    void setUidMode(int code, int uid, int mode) throws RemoteException;

    int checkOperation(int code, int uid, String packageName) throws RemoteException;

    abstract class Stub extends Binder implements IAppOpsService {

        public static IAppOpsService asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }
    }
}
