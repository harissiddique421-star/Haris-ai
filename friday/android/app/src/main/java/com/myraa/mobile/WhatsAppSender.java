package com.myraa.mobile;

/**
 * The original "FRIDAY WhatsApp send" accessibility switch. It is now the same control layer as
 * "FRIDAY phone control" (see PhoneController), so a user who turned on either switch gets both
 * WhatsApp auto-send and phone control. Kept as its own class so the switch stays on for people
 * who already enabled it.
 */
public final class WhatsAppSender extends PhoneController {}
