package com.osmanevski.ezgi

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Uygulama ikonu seçenekleri: manifest'teki activity-alias'lar. Sıra Ayarlar'daki sıradır. */
enum class AppIcon(val alias: String, val label: String, val preview: Int) {
    MINIMAL("IkonMinimal", "Minimal", R.drawable.ic_fg_minimal),
    PLAK("IkonPlak", "Plak + iğne", R.drawable.ic_launcher_fg),
    SADE("IkonSade", "Sade plak", R.drawable.ic_fg_sade),
    HALKA("IkonHalka", "Halkalı", R.drawable.ic_fg_halka),
    DESEN("IkonDesen", "Desen", R.drawable.ic_fg_desen);

    private fun component(context: Context) = ComponentName(context.packageName, "${context.packageName}.$alias")

    companion object {
        fun current(context: Context): AppIcon = entries.firstOrNull { icon ->
            when (context.packageManager.getComponentEnabledSetting(icon.component(context))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == MINIMAL // manifest'te yalnız Minimal açık
                else -> false
            }
        } ?: MINIMAL

        /** Önce yenisini aç, sonra eskileri kapat: arada başlatıcıda hiç ikon kalmasın. */
        fun select(context: Context, icon: AppIcon) {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(icon.component(context),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            entries.filter { it != icon }.forEach {
                pm.setComponentEnabledSetting(it.component(context),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
