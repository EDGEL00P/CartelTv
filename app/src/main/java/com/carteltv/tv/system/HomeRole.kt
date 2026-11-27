package com.carteltv.tv.system

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.os.Build

/** Uses Android's supported role flow instead of relying only on accessibility window redirection. */
object HomeRole {
    const val REQUEST_CODE = 4713

    fun isHome(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 29 && ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true

    fun claim(activity: Activity): String {
        if (Build.VERSION.SDK_INT < 29) return "Home role is not available on this device"
        val roles = activity.getSystemService(RoleManager::class.java)
            ?: return "Home role is not available on this device"
        return when {
            !roles.isRoleAvailable(RoleManager.ROLE_HOME) -> "Home role is not available on this device"
            roles.isRoleHeld(RoleManager.ROLE_HOME) -> "Cartel is already the home app"
            else -> {
                activity.startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), REQUEST_CODE)
                "Confirm Cartel as the home app"
            }
        }
    }

    fun resultMessage(ctx: Context, resultCode: Int): String =
        if (resultCode == Activity.RESULT_OK && isHome(ctx)) "Cartel is now the home app"
        else "Home app was not changed"
}
