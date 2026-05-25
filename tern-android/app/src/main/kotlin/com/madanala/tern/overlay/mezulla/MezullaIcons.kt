package com.madanala.tern.overlay.mezulla

/**
 * Nerd Font icon glyphs for Mezulla UI elements.
 * Each icon is a single Unicode character from the Material Design
 * or Font Awesome sets bundled in JetBrains Mono Nerd Font.
 *
 * To find more icons: https://www.nerdfonts.com/cheat-sheet
 * To swap an icon: change the string constant here. Every
 * composable that uses MezullaIcons picks up the change.
 */
object MezullaIcons {
    // Peer status
    const val PEER = "󰀂"          // 󰀂 nf-md-account (person silhouette)
    const val PEER_LOST = "󰀦"     // 󰀦 nf-md-account_off (person crossed out)
    const val PEER_ALERT = "󰀃"    // 󰀃 nf-md-account_alert (person with !)

    // Arrows for climb/sink
    const val ARROW_UP = "󰅞"      // 󰅞 nf-md-arrow_up_bold (thick up arrow)
    const val ARROW_DOWN = "󰅆"    // 󰅆 nf-md-arrow_down_bold (thick down arrow)

    // Navigation
    const val COMPASS = "󰖌"       // 󰖌 nf-md-compass (compass rose)
    const val NAVIGATION = "󰆲"    // 󰆲 nf-md-navigation (navigation arrow — rotatable)

    // Info
    const val ALTITUDE = "󰘔"      // 󰸔 nf-md-image_filter_hdr (mountain peak)
    const val CLOCK = "󰕂"         // 󰕂 nf-md-clock_outline (clock face)
    const val SPEED = "󰗺"         // 󰗺 nf-md-speedometer (speedometer)

    // Connection
    const val SIGNAL = "󰕐"        // 󰕐 nf-md-signal (signal bars)
    const val BLUETOOTH = "󰂚"     // 󰂚 nf-md-bluetooth (bluetooth icon)
    const val LINK_OFF = "󰆉"      // 󰆉 nf-md-link_off (broken link)

    // Weather (Weather Icons set)
    const val WIND = ""                // nf-weather-strong_wind
    const val CLOUD = ""               // nf-weather-cloud
    const val STORM = ""               // nf-weather-storm_showers

    // Alerts
    const val WARNING = "󰀼"       // 󰀼 nf-md-alert (triangle !)
    const val SOS = "󰒷"           // 󰒷 nf-md-alarm_light (alarm/siren)

    // Battery
    const val BATTERY_FULL = "󰁹"  // 󰁹 nf-md-battery
    const val BATTERY_LOW = "󰁮"   // 󰁮 nf-md-battery_20
}
