# Texts, calls and /internet

All of these need a phone, an active SIM with service, and a plan that includes them.

## Who you can reach

`/text` and `/call` accept any of:

- a **phone number**: `297-7402` or `2977402`
- a **contact name** from your contacts
- an **online player's name** (their current number is used)

You can only reach someone on an **active** SIM that has service.

## Texts

```
/text <number|contact|player> <message>
```

Or use **Messages → New message** on the phone.

- Delivery takes a moment, faster on better bands.
- If the recipient can't be reached, the text **waits** and is delivered once they can be, for up to 10 minutes.
- Received texts are kept in **Messages** (up to 45):
    - Click to reply.
    - Right-click to save the sender as a contact.
    - Shift-click to delete.
- Texts don't use mobile data.

## Calls

```
/call start <number|contact|player>
/call accept   ·   /call deny   ·   /call end
```

- The person you call sees your **contact name** if they've saved you, otherwise your number. They get clickable Accept and Decline buttons.
- While in a call, **your normal chat goes only to the other person**.
- At 1 bar, calls get choppy (some characters are dropped).
- If either of you loses service, the call **drops**.
- Players with **Do Not Disturb** on can't be called.

## Emergency: 909

`/call start 909` or the **Emergency** button. It works without a plan as long as any tower reaches you and you have a phone. For now it only shows a message.

## Contacts

**Contacts** on the phone:

- Click a contact to call, right-click to text.
- Shift-click to rename, shift-right-click to delete.
- Add one by typing a number, or pick from online players who have a SIM.

## /internet

A second chat channel, separate from public chat, for players with mobile data.

```
/internet           toggle internet mode (your chat goes to the internet)
/internet <message> send one message
/net                alias
```

Messages look like:

```
[NET] bladestech Nortus-Telecom P+: hello
```

The carrier is shown in its colours, followed by your phone model's initial (C, E or P) and **+** for dual-SIM models. Hover your name for the full phone name and band.

**It costs data:**

- The **sender** pays **1 MB per character**.
- **Every receiver** pays **0.1 MB per character**.
- Players without data service, or whose plan blocks them when they're out of data, miss the message.
- Messages are delayed by the sender's ping, more on a throttled connection.

Calls take priority: while you're in a call, your chat goes to the call.
