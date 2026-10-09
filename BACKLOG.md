# Parchi backlog

Parked on purpose. Review before the wider seller version.

## Product

| Item | Why parked | Who needs it later |
|---|---|---|
| Item code and barcode, with scan | A dairy scans nothing | Grocery and retail shops |
| Product description | Never reaches a thermal bill | Sellers who send PDF bills |
| Without tax dropdown on each price | One global setting is simpler | GST heavy sellers |
| Separate Pricing and Stock tabs | One scroll is fewer taps | Sellers with long product forms |
| Default discount on sale price | Discount belongs on the bill here | Retail with MRP and offers |
| Product photo | Dad knows his own products | Grocery, where staff pick by picture |
| Batch and expiry tracking | Not raised yet | Anyone selling packaged goods |

## Party

| Item | Why parked |
|---|---|
| Bulk import from phone contacts and Excel | Phase 1 is 200 parties typed once |
| Multiple shipping addresses | One delivery point per party today |
| Four custom fields | No use case yet |
| Shop photo and visiting card | No use case yet |
| Loyalty points | Not a dairy behaviour |
| Returnable crates and cans count | Real need, but a screen of its own |
| Standing order, the usual daily order | Decision pending |
| Auto bill a party's calls without approval | For parties who order the same things daily, his call bills and prints with no check. Parked until the AI matching has run clean for a few weeks |
| Also called, party nicknames | Removed from the form to keep it short. Useful later for voice billing and search |
| Route or area, with delivery sequence | Removed from the form. Comes back with route wise bill printing for dispatch |
| Old due on the party form | Removed from the form. Opening balance to be entered from the party ledger instead |

## Bill by voice, admin side

Sunny speaks in Hindi and the app does the whole thing. Not the customer call flow, this is the owner talking to his own app.

| What he says | What the app must do |
|---|---|
| "Sunny ka bill banao, paneer 1 kg, chaap 2 kg, date 1 October 2026, isme 5% discount add kar do" | Make a new bill, match the party, match the products, set the date, apply bill discount, show it for one tap save |
| "Sunny ke bill number 214 mein ek aur product add kar do" | Open that bill, add the item, recalculate, save |
| "Sunny ke 500 rupay receive hue, iski entry kar do" | Record a payment of 500 against that party and knock it off the oldest open bill |

Notes for when we build it: one mic button that stays on every screen, the speech goes to Gemini with the current party and product list as context, and the result is always shown as a filled form for one tap confirm, never saved silently. Edit and delete by voice need a stricter confirm than create.


## App wide

| Item | Why parked |
|---|---|
| Web version synced with the app | Promised for later, needs a backend |
| Outbound calling, agent calls the customer for his order | From the dairy agent vision, after inbound works |
| Follow up reminder for customers who have not called | Same, after inbound works |
| WhatsApp approvals | Needs the paid business API, Telegram covers it for now |
| Play Store release | Blocked by SMS and all files access rules, sideload only |
