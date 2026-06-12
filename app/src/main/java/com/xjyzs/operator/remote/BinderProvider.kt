package com.xjyzs.operator.remote

import android.os.Binder
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log

class BinderProvider : ContentProvider() {
    companion object {
        const val METHOD_SEND_BINDER = "send_binder"
        const val EXTRA_BINDER = "extra_binder"
        @Volatile
        var remoteBinder: IBinder? = null
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        // 拒绝非 shell/root 调用
        val callingUid = Binder.getCallingUid()
        if (callingUid != 2000 && callingUid != 0) return null

        if (method == METHOD_SEND_BINDER && extras != null) {
            val binder = extras.getBinder(EXTRA_BINDER)
            if (binder != null) {
                remoteBinder = binder
                return Bundle().apply { putBoolean("result", true) }
            }
        }
        return super.call(method, arg, extras)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}