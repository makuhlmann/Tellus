package com.yucareux.tellus.integration.xaero;

import java.util.Locale;

/**
 * Builds the command Xaero's Minimap intercepts on the client to open its "New Waypoint" screen pre-filled: the same
 * command its "[Add]" links in shared-waypoint chat messages run. The minimap cancels it before it reaches the
 * server, so it needs no API and no dependency on the closed-source mod. Format, as read from Xaero's Minimap 26.6.2:
 * {@code xaero_waypoint_add:<name>:<initials>:<x>:<y, or ~ for none>:<z>:<colour>:<rotate>:<yaw>}.
 */
public final class XaeroWaypointCommand {
   /** Xaero rejects longer names and initials. */
   public static final int MAX_NAME_LENGTH = 32;
   public static final int MAX_INITIALS_LENGTH = 3;
   /** Xaero's waypoint colours follow the chat colour codes, so 12 is red. */
   public static final int RED = 12;
   private static final String PREFIX = "xaero_waypoint_add:";
   private static final String FALLBACK_NAME = "Waypoint";
   /** How Xaero writes a colon inside a field, since the fields are separated by colons. */
   private static final String ENCODED_COLON = "^col^";

   private XaeroWaypointCommand() {
   }

   /** Without a height, so Xaero shows the waypoint at the player's own height. */
   public static String build(String name, int x, int z, int color) {
      String waypointName = shorten(clean(name), MAX_NAME_LENGTH);
      if (waypointName.isEmpty()) {
         waypointName = FALLBACK_NAME;
      }
      return PREFIX
         + waypointName.replace(":", ENCODED_COLON)
         + ":" + initials(waypointName)
         + ":" + x
         + ":~"
         + ":" + z
         + ":" + Math.max(0, color)
         + ":false:0";
   }

   /** The first letter of the name, or its first character when it has no letter. */
   private static String initials(String name) {
      int first = name.codePoints().filter(Character::isLetterOrDigit).findFirst().orElse(name.codePointAt(0));
      String initial = new String(Character.toChars(first)).toUpperCase(Locale.ROOT);
      return initial.contains(":") ? "W" : shorten(initial, MAX_INITIALS_LENGTH);
   }

   private static String clean(String name) {
      StringBuilder cleaned = new StringBuilder();
      (name == null ? "" : name).codePoints().filter(codePoint -> !Character.isISOControl(codePoint)).forEach(cleaned::appendCodePoint);
      return cleaned.toString().strip();
   }

   /** At most {@code maxLength} UTF-16 units, the way Xaero counts, without splitting a character. */
   private static String shorten(String text, int maxLength) {
      int end = 0;
      while (end < text.length()) {
         int next = end + Character.charCount(text.codePointAt(end));
         if (next > maxLength) {
            break;
         }
         end = next;
      }
      return text.substring(0, end).strip();
   }
}
