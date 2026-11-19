# ride · DiDi Ride plugin

Through the DiDi MCP it covers the full flow from estimates to creating, cancelling and tracking orders:

- `ride_place_search`: place search, listing every place returned with its name, full address, city and coordinates
- `ride_estimate`: every vehicle type with reference and discounted price, discounts, currency, pickup wait, distance, duration, vehicle type ID and category, phone tail number and traceId, as the basis for booking
- `ride_create_order`: creates a real DiDi order after the user confirms
- `ride_cancel_order`: cancels the order after the user confirms
- `ride_app_link`: generates a booking link for the DiDi app or mini program
- `ride_order_status`: order status, driver, vehicle type, plate, phone, rating, distance to the pickup point, ETA and order time; rate limiting is reported clearly for a later retry
- `ride_driver_location`: the driver's live coordinates and address, plus driver, plate, distance, ETA, status and update time as returned by the API

The DiDi key is stored encrypted on the device (never echoed on the page) and verified on save. "Advanced settings" switches between production and demo mode (demo mode uses simulated data and creates no real orders). Creating and cancelling both show a native confirmation, and cancelling asks again. Payment happens in DiDi; the plugin never pays automatically.

Build:

```bash
./scripts/gradlew-locked :ride:testDebugUnitTest :ride:verifyPlugin
```
