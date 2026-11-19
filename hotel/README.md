# hotel · RollingGo Hotels plugin

Connects to RollingGo so you can search hotels in chat, see live room rates, create pending orders, and check order lists and single-order details. The plugin page handles service install, update and sign-in; no API key is required.

## Page

- install / update the hotel service;
- sign in to RollingGo: opens the authorization link automatically, or open or copy it manually;
- sign out;
- view the runtime, service version and sign-in state; failures retry in place, sign-in can be cancelled.

## Tools

- `hotel_search`: search hotels by place, dates, guests and star rating; the text gives star rating, reference price, distance, address, all tags and facilities, hotelId and booking link; the card shows the first hotel as an image hero and expands the remaining hotels;
- `hotel_detail`: groups rate plans by room type, with nightly average, total for the check-in and check-out dates, bed type, breakfast and count, all cancellation policies, rate plan ID, dates, room information (area/floor/capacity/window/network) and room photos; the card lists every room type and expands all plans within a group; totals converted from averages are marked "based on N nights";
- `hotel_book`: locks the price and creates a pending order after the user confirms, returning the official payment link; optional `rate_plan_id`, and a native picker appears when one room type has several plans;
- `hotel_orders`: shows order number, hotel, room type, dates, nights, room count, breakfast, cancellation policy, status, total, order time and payment link; all orders can be expanded;
- `hotel_order_detail`: shows every field of a single order by order number, including hotel confirmation number, hotel address/phone/coordinates, room type/bed type/guests, contact, payment deadline, cancellation policy and free-cancellation deadline; the card expands to all fields.

Search and room types are read-only. Booking shows a native confirmation first and only creates a pending order; the user pays on RollingGo. RollingGo quotes a nightly average, so the plugin converts it to a total for the stay and marks the conversion. Raw CLI output is returned with the result; cards only present it readably.

## Skill

`superpi-hotel` tells the agent to prefer the tools above. It calls `hotel_book` to lock the price and create a pending order only after the user has explicitly confirmed the hotel, room type, rate plan, dates and total and provided contact details. When one room type has several rate plans, it asks the user to choose and passes the plan ID to `hotel_book`. For a confirmation number, hotel phone number, guest name or payment deadline it uses `hotel_order_detail`. The plugin never pays on the user's behalf.

## Build

```bash
./scripts/gradlew-locked :hotel:test :hotel:verifyPlugin
```
