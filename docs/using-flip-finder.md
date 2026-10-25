# Using Flip Finder

Flipping means buying an item on the Grand Exchange at the price sellers are
dumping it for and selling it at the price buyers are paying, keeping the
difference. Flip Finder prices every tradeable item as a flip and ranks them
by what they could earn.

## Finding a flip

1. **Set your budget**, such as `10m` or `500k`. Items that cost more than you
   have disappear, and each flip is sized to what you can afford.
2. **Read the top of the list.** It is ranked by estimated profit over the
   next four hours, the length of one buy limit.
3. **Open an item** for its full breakdown: the tax, how many you could buy,
   how fast it is trading, and links to the item's wiki and price history.
4. **Place a buy offer at the "Buy at" price** and a sell offer at the "Sell
   at" price once it fills.

## Asking the chat

On the [homelab](homelab.md), Flip Finder opens on a chat with the homelab's
local language model. Ask it things like:

- What should I flip right now?
- What could I make with 50M?
- Is dragon bones a good flip?
- Is dragon bones trading more than usual?
- Which of my buy limits reset soonest?

Once the [RuneLite plugin](runelite-plugin.md) has reported your account, the
panel beside the chat shows what it knows: your coins, Grand Exchange offers
and the buy limits you have used. Answers use your coins as the budget unless
you name one, stick to free-to-play items on a free-to-play account, and leave
out what you have already bought this buy-limit window.

The homelab starts its language model when Flip Finder starts, so the first
answer after that can take up to a couple of minutes while the model loads; the
chat says when it is waiting for it. Later answers start right away.

The model looks flips up with the same ranking as the table, then summarizes
the best few. **Based on N flips** under an answer opens the rows it read, so
you can check its numbers. Language models can still misread or leave things
out, so check the price before you commit a lot of coins. The chat can also
say whether an item is trading normally; see [Is this normal?](#is-this-normal).

## Is this normal?

A big margin on an item that suddenly trades ten times its usual volume is
often a short-lived spike: the price moves before your offers fill. Flip Finder
keeps recent trading history to tell whether an item's latest volume, price
and margin are typical:

- **Volume** is compared with the same time of day on earlier days, since the
  Grand Exchange is much busier in some hours than others. Three times the usual
  volume or more counts as unusually high, and the last hour shows whether a
  surge has lasted or was a short burst.
- **Price** is compared with a day, a week and 30 days ago.
- **Margin** is compared with the usual gap between buy and sell prices. Three
  times the usual width or more often closes before offers fill.

A flip with unusually high volume or an unusually wide margin gets an
**unusual** tag in the table; hover over it for the reason. Opening an item
shows the full comparison under **Compared with usual**. Expect a fair share of
the top flips to be tagged: a margin that is wider than usual right now is
often what put a flip at the top.

The standalone version fetches the last two days of history when it starts,
within about 15 seconds, and keeps it in memory. The homelab version keeps a
week of five-minute trading and a month of hourly trading on disk, and its chat
mentions unusual flips when it suggests them. **Based on N days of history**
under a chat answer shows the comparison it used.

## The columns

- **Buy at / sell at:** the latest instant-sell (low) and instant-buy (high)
  prices. The *Prices* switch can use five-minute average prices instead.
- **Margin:** sell price minus Grand Exchange tax minus buy price, per item.
  Losing flips are shown in red.
- **ROI:** the margin as a share of the buy price.
- **Buy limit:** how many you can buy every four hours. About 500 items have
  no documented limit; they show *unknown* and are not estimated, because
  assuming no limit would overstate them.
- **Potential:** the margin across one whole buy limit, or across as many as
  your budget affords.
- **5-min volume:** how many traded at the low and at the high price in the
  latest five-minute window. Your buy offer fills from trades at the low price
  and your sell offer from trades at the high price, so the slower side sets
  the pace. The bar shows how much of your quantity that pace fills in four
  hours.
- **Est. profit:** the margin across what could fill in four hours at that
  pace, capped by the buy limit and your budget. The note underneath says
  which of the three is the cap.
- **Last trade:** how long ago the older of the two latest trades happened.

Est. profit assumes every trade at your price is yours, so treat it as an
upper bound: other flippers are competing for the same volume.

## Grand Exchange tax

The seller pays 2% of the sale price, rounded down and capped at 5M per item.
Sales under 50 gp pay nothing, and so do a short list of exempt items such as
some tools, low-level food and teleports. [Price data](price-data.md) lists
the sources.

Bonds are exempt too, but they are never listed as flips. A bond bought on the
Grand Exchange comes back untradeable, and making it tradeable again costs 10%
of its guide price, far more than a bond's margin.

## Filters

By default the list hides items that have not traded on both sides within the
last hour or traded fewer than ten times in the last five minutes. Stale
prices produce huge margins that no one is actually trading at. Change either
filter, pick free-to-play or members items, or search by name. A search that
matches items hidden by your filters offers to clear them.

Filters, sorting, the budget and the theme are remembered in your browser. The
sun and moon button switches between light and dark; until you use it, the
page follows your system setting.
