package net.sourceforge.kolmafia.shop;

import static internal.helpers.Networking.html;
import static internal.helpers.Player.withSkill;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;

import internal.helpers.Cleanups;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.sourceforge.kolmafia.AdventureResult;
import net.sourceforge.kolmafia.AdventureResult.MeatResult;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

public class ShopRowTest {

  public static Map<AdventureResult, Integer> currencies(final List<ShopRow> rows) {
    Map<AdventureResult, Integer> result = new TreeMap<>();
    for (ShopRow row : rows) {
      for (AdventureResult cost : row.getCosts()) {
        AdventureResult key = cost.getInstance(1);
        int count = result.containsKey(key) ? result.get(key) : 0;
        result.put(key, count + 1);
      }
    }
    return result;
  }

  public static void printCurrencies(Map<AdventureResult, Integer> currencies) {
    for (var entry : currencies.entrySet()) {
      System.out.println(entry.getKey().getName() + " -> " + entry.getValue());
    }
  }

  @Nested
  class ArmoryAndLeggery {
    // shop.php?whichshop=armory
    // test_shop_armory_and_leggery.html

    static AdventureResult meat = new MeatResult(1);
    static AdventureResult adobe = new AdventureResult("adobe assortment", 1);
    static AdventureResult moss = new AdventureResult("moss mulch", 1);

    @Test
    public void canParseArmoryAndLeggeryWithMeat() {
      String html = html("request/test_shop_armory_and_leggery.html");
      var inventory = ShopRow.parseShop(html, true);
      var currencies = currencies(inventory);
      assertEquals(45, inventory.size());
      assertEquals(33, currencies.get(meat));
      assertEquals(6, currencies.get(adobe));
      assertEquals(6, currencies.get(moss));
    }

    @Test
    public void canParseArmoryAndLeggeryWithoutMeat() {
      String html = html("request/test_shop_armory_and_leggery.html");
      var inventory = ShopRow.parseShop(html, false);
      var currencies = currencies(inventory);
      assertEquals(12, inventory.size());
      assertNull(currencies.get(meat));
      assertEquals(6, currencies.get(adobe));
      assertEquals(6, currencies.get(moss));
    }
  }

  @Nested
  class Grandma {
    // shop.php?whichshop=grandma
    // test_shop_grandma.html

    static AdventureResult helmet = new AdventureResult("aerated diving helmet", 1);
    static AdventureResult mask = new AdventureResult("crappy Mer-kin mask", 1);
    static AdventureResult tailpiece = new AdventureResult("crappy Mer-kin tailpiece", 1);
    static AdventureResult dull = new AdventureResult("dull fish scale", 1);
    static AdventureResult facecowl = new AdventureResult("Mer-kin facecowl", 1);
    static AdventureResult headguard = new AdventureResult("Mer-kin headguard", 1);
    static AdventureResult thighguard = new AdventureResult("Mer-kin thighguard", 1);
    static AdventureResult waistrope = new AdventureResult("Mer-kin waistrope", 1);
    static AdventureResult pristine = new AdventureResult("pristine fish scale", 1);
    static AdventureResult chaps = new AdventureResult("sea chaps", 1);
    static AdventureResult lace = new AdventureResult("sea lace", 1);
    static AdventureResult leather = new AdventureResult("sea leather", 1);
    static AdventureResult fins = new AdventureResult("teflon swim fins", 1);

    @Test
    public void canParseGrandma() {
      String html = html("request/test_shop_grandma.html");
      var inventory = ShopRow.parseShop(html, true);
      var currencies = currencies(inventory);
      assertEquals(17, inventory.size());
      assertEquals(1, currencies.get(helmet));
      assertEquals(2, currencies.get(mask));
      assertEquals(2, currencies.get(tailpiece));
      assertEquals(1, currencies.get(dull));
      assertEquals(1, currencies.get(facecowl));
      assertEquals(1, currencies.get(headguard));
      assertEquals(1, currencies.get(thighguard));
      assertEquals(1, currencies.get(waistrope));
      assertEquals(9, currencies.get(pristine));
      assertEquals(1, currencies.get(chaps));
      assertEquals(4, currencies.get(lace));
      assertEquals(1, currencies.get(leather));
      assertEquals(1, currencies.get(fins));
    }
  }

  @Nested
  class Crimbo23PirateArmory {
    // shop.php?whichshop=crimbo23_pirate_armory
    // test_shop_crimbo23_pirate_armory.html

    static AdventureResult flotsam = new AdventureResult("Crimbuccaneer flotsam", 1);
    static AdventureResult shirt = new AdventureResult("Crimbuccaneer shirt", 1);
    static AdventureResult pegfinger = new AdventureResult("pegfinger", 1);
    static AdventureResult blunderbuss = new AdventureResult("sawed-off blunderbuss", 1);
    static AdventureResult hammer = new AdventureResult("shipwright's hammer", 1);

    @Test
    public void canParseCrimbo23PirateArmory() {
      String html = html("request/test_shop_crimbo23_pirate_armory.html");
      var inventory = ShopRow.parseShop(html, true);
      var currencies = currencies(inventory);
      assertEquals(5, inventory.size());
      assertEquals(1, currencies.get(flotsam));
      assertEquals(1, currencies.get(shirt));
      assertEquals(1, currencies.get(pegfinger));
      assertEquals(1, currencies.get(blunderbuss));
      assertEquals(1, currencies.get(hammer));
    }
  }

  @Nested
  class Crimbo24Factory {
    // shop.php?whichshop=crimbo24_factory
    // test_shop_crimbo24_factory.html

    static AdventureResult easter = new AdventureResult("Spirit of Easter", 1);
    static AdventureResult patrick = new AdventureResult("Spirit of St. Patrick's Day", 1);
    static AdventureResult veterans = new AdventureResult("Spirit of Veteran's Day", 1);

    // Crimbo Factory    ROW1538    lucky moai statuette    Spirit of Easter (50)    Spirit of St.
    // Patrick's Day (50)
    // Crimbo Factory    ROW1537    egg gun    Spirit of Easter (200)    Spirit of Veteran's Day
    // (200)
    // Crimbo Factory    ROW1540    lucky army helmet    Spirit of St. Patrick's Day (350)    Spirit
    // of Veteran's Day (75)
    // Crimbo Factory    ROW1533    Secrets of the Master Egg Hunters    Spirit of Easter (500)
    // Crimbo Factory    ROW1534    How to Lose Friends and Attract Snakes    Spirit of St.
    // Patrick's Day (500)
    // Crimbo Factory    ROW1535    Covert Ops for Kids    Spirit of Veteran's Day (500)

    @Test
    public void canParseCrimbo24Factory() {
      String html = html("request/test_shop_crimbo24_factory.html");
      var inventory = ShopRow.parseShop(html, true);
      var currencies = currencies(inventory);
      assertEquals(6, inventory.size());
      assertEquals(3, currencies.get(easter));
      assertEquals(3, currencies.get(patrick));
      assertEquals(3, currencies.get(veterans));
    }
  }

  @Nested
  class SeptEmberCenser {
    // shop.php?whichshop=september
    // test_shop_september.html

    static AdventureResult ember = AdventureResult.tallyItem("Ember", 1, false);

    // Sept-Ember Censer	ROW1516	bembershoot	Ember
    // Sept-Ember Censer	ROW1510	blade of dismemberment	Ember
    // Sept-Ember Censer	ROW1515	embers-only jacket	Ember
    // Sept-Ember Censer	ROW1518	hat of remembering	Ember
    // Sept-Ember Censer	ROW1517	wheel of camembert	Ember
    // Sept-Ember Censer	ROW1512	Mmm-brr! brand mouthwash	Ember (2)
    // Sept-Ember Censer	ROW1520	head of emberg lettuce	Ember (2)
    // Sept-Ember Censer	ROW1513	Septapus summoning charm	Ember (2)
    // Sept-Ember Censer	ROW1519	throwin' ember	Ember (2)
    // Sept-Ember Censer	ROW1514	structural ember	Ember (4)
    // Sept-Ember Censer	ROW1511	miniature Embering Hulk	Ember (6)

    @Test
    public void canParseSeptEmberCenser() {
      String html = html("request/test_shop_september.html");
      var inventory = ShopRow.parseShop(html, true);
      var currencies = currencies(inventory);
      assertEquals(11, inventory.size());
      assertEquals(11, currencies.get(ember));
    }
  }

  @Nested
  class PrimordialSoupKitchen {
    static AdventureResult chroner = new AdventureResult("Chroner", 1, false);
    static AdventureResult bisque = new AdventureResult("bacteria bisque", 1);
    static AdventureResult chowder = new AdventureResult("ciliophora chowder", 1);
    static AdventureResult cream = new AdventureResult("cream of chloroplasts", 1);
    static AdventureResult elbow = new AdventureResult("protogenetic chunklet (elbow)", 1);
    static AdventureResult flagellum = new AdventureResult("protogenetic chunklet (flagellum)", 1);
    static AdventureResult lips = new AdventureResult("protogenetic chunklet (lips)", 1);
    static AdventureResult muscle = new AdventureResult("protogenetic chunklet (muscle)", 1);
    static AdventureResult synapse = new AdventureResult("protogenetic chunklet (synapse)", 1);

    @Test
    public void canParsePrimordialSoupKitchen() {
      String html = html("request/test_shop_twitchsoup.html");
      var shopName = ShopRequest.parseShopNameInResponse(html);
      assertEquals("The Primordial Soup Kitchen", shopName);

      var shopId = ShopRequest.parseShopId(html);
      assertEquals("twitchsoup", shopId);

      var shopRows = ShopRow.parseShop(html, true);
      assertEquals(18, shopRows.size());

      var derivedCurrencies = ShopRow.deriveCurrencies(shopRows);
      assertEquals(9, derivedCurrencies.size());

      var parsedCurrencies = ShopRow.parseCurrencies(html);
      assertEquals(9, parsedCurrencies.size());

      assertTrue(derivedCurrencies.contains(chroner));
      assertTrue(derivedCurrencies.contains(bisque));
      assertTrue(derivedCurrencies.contains(chowder));
      assertTrue(derivedCurrencies.contains(cream));
      assertTrue(derivedCurrencies.contains(elbow));
      assertTrue(derivedCurrencies.contains(flagellum));
      assertTrue(derivedCurrencies.contains(lips));
      assertTrue(derivedCurrencies.contains(muscle));
      assertTrue(derivedCurrencies.contains(synapse));

      assertEquals(derivedCurrencies, parsedCurrencies);
    }
  }

  @Nested
  class NPCStores {
    // shop.php?whichshop=madeline
    // test_shop_madeline.html

    @Test
    public void canParseMadelineAtDiscountedPrice() {
      // I visited Madeline with the Five Fingered Discount skill.
      var cleanups = new Cleanups(withSkill("Five Finger Discount"));
      try (cleanups) {
        String html = html("request/test_shop_madeline.html");
        var inventory = ShopRow.parseShop(html, true);
        String prices =
            inventory.stream()
                .map((ShopRow sr) -> sr.getCosts()[0].getCount())
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        // These are the actual prices Madeline charges.
        assertThat(prices, is("25,40,60,60,100,400,400"));
      }
    }

    @Test
    public void canParseMadelineAtFullPrice() {
      // I visited Madeline with the Five Fingered Discount skill.
      String html = html("request/test_shop_madeline.html");
      var inventory = ShopRow.parseShop(html, true);
      String prices =
          inventory.stream()
              .map((ShopRow sr) -> sr.getCosts()[0].getCount())
              .map(String::valueOf)
              .collect(Collectors.joining(","));
      // These are the discounted prices Madeline quoted.
      assertThat(prices, is("23,38,57,57,95,380,380"));
    }
  }
}
