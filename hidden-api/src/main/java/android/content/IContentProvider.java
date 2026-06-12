package android.content;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/**
 * Hidden API stub for IContentProvider.
 */
public interface IContentProvider extends IInterface {

    Bundle call(String callingPkg, String method, String arg, Bundle extras) throws RemoteException;

    Bundle call(String callingPkg, String authority, String method, String arg, Bundle extras)
            throws RemoteException;

    Bundle call(String callingPkg, String attributionTag, String authority, String method, String arg,
                Bundle extras) throws RemoteException;

    Bundle call(AttributionSource attributionSource, String authority, String method, String arg,
                Bundle extras) throws RemoteException;

    abstract class Stub extends Binder implements IContentProvider {
        public static IContentProvider asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }
    }
}
