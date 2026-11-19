# amap · Amap Maps plugin

Search places in chat, look at nearby POIs, turn coordinates back into addresses, plan driving, transit, walking and cycling routes, and check the weather. Results appear as cards; tap a card to view it in Amap and navigate there. Navigation and ride-hailing happen inside Amap.

The four card types in the answer area each have their own layout: places and nearby use a map hero with numbered place rows, reverse geocoding uses a large address with a location map, routes use a per-leg timeline with total time, and weather uses a large current temperature with bars for the coming days. Place, nearby and coordinate cards carry a static map preview: tap the map to open Amap, top right to view full screen. The preview uses the same Web Service key; when the static map service is not enabled, a placeholder note appears without affecting results.

## Configuration

1. Create an app on the Amap open platform and request a Web Service key.
2. Open the "Amap Maps" plugin page, paste the key and save; the plugin verifies the connection and saves only on success.
3. The key stays on the device and is used only to request map data from Amap.

## Tools

| Tool | Purpose |
|---|---|
| `amap_place_search` | Search places by keyword and city |
| `amap_around_search` | Search places near a coordinate |
| `amap_regeo` | Convert coordinates into an administrative address |
| `amap_route_driving` | Driving routes |
| `amap_route_transit` | Transit, subway and train routes |
| `amap_route_walking` | Walking routes |
| `amap_route_bicycling` | Cycling routes |
| `amap_weather` | City weather forecast |

All tools are read-only queries; they never book, navigate or hail a ride — the user does those in Amap. Results carry the full facts such as name, address, type, coordinates, distance, duration, step-by-step directions and weather values, so the model does not need to infer data from cards.

## Page

The plugin page handles key configuration, connection state and error retry; places, coordinates, routes and weather in chat use their own layouts, the map opens Amap, and loading, empty and failure states each have a matching presentation.

## Build

```bash
./scripts/gradlew-locked :amap:testDebugUnitTest :amap:verifyPlugin
```
