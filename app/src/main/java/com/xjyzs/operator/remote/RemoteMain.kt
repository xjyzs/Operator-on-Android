package com.xjyzs.operator.remote

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import kotlin.system.exitProcess

object RemoteMain {
    private var mToken: IBinder? = null
    private var mProviderBinder: IBinder? = null
    private var mDeathRecipient: IBinder.DeathRecipient? = null
    private var mService: InputControlService? = null

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val service = InputControlService()
            mService = service
            val bundle = Bundle()
            bundle.putBinder("extra_binder", service)

            // 3. 通过纯反射获取 ActivityManager
            val amNativeClass = Class.forName("android.app.ActivityManagerNative")
            val getDefaultMethod = amNativeClass.getMethod("getDefault")
            val activityManager = getDefaultMethod.invoke(null)

            if (activityManager == null) {
                return
            }

            // 4. 调用 getContentProviderExternal 获取 ContentProviderHolder 对象
            val authority = "com.xjyzs.operator.remote.binder"
            mToken = Binder() // 赋值给成员变量保持强引用
            var holder: Any? = null
            val amClass = activityManager.javaClass

            val currentUserId = try {
                Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null) as Int
            } catch (e: Exception) {
                0
            }

            try {
                // Android 10+ 参数签名
                val m = amClass.getMethod(
                    "getContentProviderExternal",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    IBinder::class.java,
                    String::class.java
                )
                holder = m.invoke(activityManager, authority, currentUserId, mToken, null)
            } catch (e: NoSuchMethodException) {
                // Android 9 及以下参数签名
                val m = amClass.getMethod(
                    "getContentProviderExternal",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    IBinder::class.java
                )
                holder = m.invoke(activityManager, authority, currentUserId, mToken)
            }

            if (holder == null) {
                return
            }

            // 5. 提取 ContentProviderHolder 中的 provider 字段（即 IContentProvider）
            val providerField = holder.javaClass.getField("provider")
            val provider = providerField.get(holder)

            if (provider == null) {
                return
            }
            callProvider(provider, "com.android.shell", authority, "send_binder", bundle)

            try {
                val providerBinder =
                    provider as? IBinder ?: provider.javaClass.getMethod("asBinder").invoke(provider) as IBinder

                mProviderBinder = providerBinder // 保持强引用

                val deathRecipient = IBinder.DeathRecipient {
                    try {
                        service.exit()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        // 2. 【关键修复】无论 service.exit() 是否抛出异常，都必须确保调用 exitProcess
                        exitProcess(0)
                    }
                }
                mDeathRecipient = deathRecipient // 保持强引用

                providerBinder.linkToDeath(deathRecipient, 0)
            } catch (e: Exception) {
                // 3. 【关键修复】不要吞噬异常。如果 App 此时刚好已经死亡，linkToDeath 会抛出异常，此时应该立即退出进程
                exitProcess(0)
            }

            Looper.prepare()
            Looper.loop()

        } catch (e: Exception) {
            e.printStackTrace()
            exitProcess(1)
        }
    }

    private fun callProvider(
        provider: Any,
        callingPkg: String,
        authority: String,
        method: String,
        extras: Bundle
    ) {
        val callMethod = provider.javaClass.declaredMethods.find { it.name == "call" }
            ?: throw NoSuchMethodException("未找到 call 方法")

        val paramTypes = callMethod.parameterTypes
        val paramCount = paramTypes.size
        val args = arrayOfNulls<Any>(paramCount)

        if (paramCount > 0 && paramTypes[0].name == "android.content.AttributionSource") {
            // Android 12+ (API 31+)
            val attributionSource = createAttributionSource()
            args[0] = attributionSource

            var stringCount = 0
            for (i in 1 until paramCount) {
                val type = paramTypes[i]
                if (type == String::class.java) {
                    when (stringCount) {
                        0 -> args[i] = authority
                        1 -> args[i] = method
                        2 -> args[i] = null
                    }
                    stringCount++
                } else if (type == Bundle::class.java) {
                    args[i] = extras
                }
            }
        } else {
            // Android 11 及以下
            var stringCount = 0
            for (i in 0 until paramCount) {
                val type = paramTypes[i]
                if (type == String::class.java) {
                    when (stringCount) {
                        0 -> args[i] = callingPkg
                        1 -> {
                            if (paramCount == 6) {
                                args[i] = null
                            } else if (paramCount == 5) {
                                args[i] = authority
                            } else {
                                args[i] = method
                            }
                        }
                        2 -> {
                            if (paramCount == 6) {
                                args[i] = authority
                            } else if (paramCount == 5) {
                                args[i] = method
                            } else {
                                args[i] = null
                            }
                        }
                        3 -> {
                            if (paramCount == 6) {
                                args[i] = method
                            } else {
                                args[i] = null
                            }
                        }
                        4 -> args[i] = null
                    }
                    stringCount++
                } else if (type == Bundle::class.java) {
                    args[i] = extras
                }
            }
        }

        callMethod.invoke(provider, *args)
    }

    private fun createAttributionSource(): Any {
        val builderClass = Class.forName("android.content.AttributionSource\$Builder")
        val builderConstructor = builderClass.getConstructor(Int::class.javaPrimitiveType)
        val builder = builderConstructor.newInstance(2000)

        val setPackageNameMethod = builderClass.getMethod("setPackageName", String::class.java)
        setPackageNameMethod.invoke(builder, "com.android.shell")

        val buildMethod = builderClass.getMethod("build")
        return buildMethod.invoke(builder)
    }
}