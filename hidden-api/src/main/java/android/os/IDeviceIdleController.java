package android.os;

/**
 * Hidden API stub for IDeviceIdleController
 */
public interface IDeviceIdleController extends IInterface {

    void addPowerSaveWhitelistApp(String packageName) throws RemoteException;

    void removePowerSaveWhitelistApp(String packageName) throws RemoteException;

    boolean isPowerSaveWhitelistApp(String packageName) throws RemoteException;

    abstract class Stub extends Binder implements IDeviceIdleController {
        public static IDeviceIdleController asInterface(IBinder obj) {
            throw new UnsupportedOperationException();
        }
    }
}
