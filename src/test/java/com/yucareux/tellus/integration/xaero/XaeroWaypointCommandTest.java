package com.yucareux.tellus.integration.xaero;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class XaeroWaypointCommandTest {
   @Test
   void writesTheFieldsInTheOrderXaerosMinimapReadsThem() {
      assertEquals(
         "xaero_waypoint_add:Map Marker:M:1494:~:-7032:12:false:0",
         XaeroWaypointCommand.build("Map Marker", 1494, -7032, XaeroWaypointCommand.RED)
      );
   }

   @Test
   void writesColonsInTheNameTheWayXaeroDecodesThem() {
      assertEquals(
         "xaero_waypoint_add:Gate^col^North:G:0:~:0:12:false:0",
         XaeroWaypointCommand.build("Gate:North", 0, 0, XaeroWaypointCommand.RED)
      );
   }

   @Test
   void keepsNamesWithinXaerosLengthLimitWithoutSplittingACharacter() {
      String name = "x".repeat(31) + "\uD83D\uDE82";
      String command = XaeroWaypointCommand.build(name, 0, 0, 0);

      assertEquals("xaero_waypoint_add:" + "x".repeat(31) + ":X:0:~:0:0:false:0", command);
   }

   @Test
   void takesTheFirstLetterAsInitialsAndFallsBackForEmptyNames() {
      assertEquals("xaero_waypoint_add:\u30DE\u30C3\u30D7:\u30DE:1:~:2:12:false:0", XaeroWaypointCommand.build("\u30DE\u30C3\u30D7", 1, 2, 12));
      assertEquals("xaero_waypoint_add:(1) Spot:1:1:~:2:12:false:0", XaeroWaypointCommand.build("(1) Spot", 1, 2, 12));
      assertEquals("xaero_waypoint_add:Waypoint:W:1:~:2:3:false:0", XaeroWaypointCommand.build(" \n ", 1, 2, 3));
   }
}
