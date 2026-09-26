package desu.inugram.helpers.maps

import desu.inugram.InuConfig
import org.telegram.messenger.MessagesController

object MapsHelper {
    @JvmStatic
    fun isHybridAvailable(): Boolean {
        return InuConfig.MAP_PROVIDER.value != InuConfig.MapProviderItem.OSM
    }

    @JvmStatic
    // MessagesController.mapProvider values:
    // -1 = disabled
    // 1 = yandex, direct
    // 2 = telegram, via inputWebFileGeoPointLocation
    // 3 = yandex, via webFile proxy
    // 4 = google, via webFile proxy
    // (any other) = google, direct
    fun overrideMapProvider(stock: Int): Int = when (InuConfig.MAP_PREVIEW_PROVIDER.value) {
        InuConfig.MapPreviewProviderItem.DEFAULT -> stock
        InuConfig.MapPreviewProviderItem.TELEGRAM -> 2
        InuConfig.MapPreviewProviderItem.GOOGLE -> 101 // override to 101 to disambiguate with server-pushed google in syncMapProvider
        InuConfig.MapPreviewProviderItem.YANDEX -> 1
        InuConfig.MapPreviewProviderItem.DISABLED -> -1
        else -> stock
    }

    fun syncMapProvider(messagesController: MessagesController) {
        messagesController.mapProvider = overrideMapProvider(messagesController.mainSettings.getInt("mapProvider", 0));
        if (messagesController.mapProvider == 101) {
            messagesController.mapKey = "AIzaSyB0y3zA4LbA04ZPaHKsr_Xt5ZQWbMftj8I" // google maps api key from TL_config in official apps
        }
    }
}