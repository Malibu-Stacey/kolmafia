package net.sourceforge.kolmafia.request;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sourceforge.kolmafia.AdventureResult;
import net.sourceforge.kolmafia.KoLCharacter;
import net.sourceforge.kolmafia.KoLmafia;
import net.sourceforge.kolmafia.RequestLogger;
import net.sourceforge.kolmafia.persistence.CoinmastersDatabase;
import net.sourceforge.kolmafia.persistence.ItemDatabase;
import net.sourceforge.kolmafia.persistence.NPCStoreDatabase;
import net.sourceforge.kolmafia.session.MallPriceManager;
import net.sourceforge.kolmafia.utilities.StringUtilities;

public class MallSearchRequest extends GenericRequest {

  private String searchString;
  private final int storeId;
  private List<PurchaseRequest> results;

  public MallSearchRequest(final int storeId) {
    super("mallstore.php");
    this.addFormField("whichstore", String.valueOf(storeId));

    this.searchString = "";
    this.storeId = storeId;
    this.results = new ArrayList<>();
  }

  public MallSearchRequest(final String searchString, final int cheapestCount) {
    this(searchString, cheapestCount, new ArrayList<>());
  }

  /**
   * Constructs a new <code>MallSearchRequest</code> which searches for the given item, storing the
   * results in the given <code>List</code>. Note that the search string is exactly the same as the
   * way KoL does it at the current time.
   *
   * @param searchString The string (including wildcards) for the item to be found
   * @param cheapestCount The number of stores to show; use a non-positive number to show all
   * @param results The list in which to store the results
   */
  public MallSearchRequest(
      final String searchString, final int cheapestCount, final List<PurchaseRequest> results) {
    super("mall.php");

    this.searchString = searchString == null ? "" : searchString.trim();
    this.storeId = 0;
    this.results = results;

    this.addFormField("pudnuggler", this.searchString);
    this.addFormField("category", "allitems");
    this.addFormField("consumable_byme", "0");
    this.addFormField("weaponattribute", "3");
    this.addFormField("wearable_byme", "0");
    this.addFormField("nolimits", "0");
    this.addFormField("max_price", "0");
    this.addFormField("sortresultsby", "price");
    this.addFormField("justitems", "0");
    this.addFormField("x_cheapest", String.valueOf(cheapestCount));
  }

  /** Search for a category */
  public MallSearchRequest(final String category, final String tiers) {
    super("mall.php");

    this.searchString = "";
    this.storeId = 0;
    this.results = new ArrayList<>();

    this.addFormField("pudnuggler", this.searchString);
    this.addFormField("category", category);
    // food_sortitemsby=name
    // booze_sortitemsby=name
    // othercon_sortitemsby=name
    this.addFormField("consumable_byme", "0");
    // hats_sortitemsby=name
    // shirts_sortitemsby=name
    // pants_sortitemsby=name
    // weapons_sortitemsby=name
    this.addFormField("weaponattribute", "3");
    // weaponhands=3
    // acc_sortitemsby=name
    // offhand_sortitemsby=name
    this.addFormField("wearable_byme", "0");
    // famequip_sortitemsby=name
    this.addFormField("nolimits", "0");
    this.addFormField("sortresultsby", "price");
    this.addFormField("justitems", "0");
    this.addFormField("max_price", "0");
    this.addFormField("x_cheapest", String.valueOf(5));
    // if no tier is 1, search all consumables. Otherwise, search only selected tiers
    this.addFormField("consumable_tier_1", tiers.contains("crappy") ? "1" : "0");
    this.addFormField("consumable_tier_2", tiers.contains("decent") ? "1" : "0");
    this.addFormField("consumable_tier_3", tiers.contains("good") ? "1" : "0");
    this.addFormField("consumable_tier_4", tiers.contains("awesome") ? "1" : "0");
    this.addFormField("consumable_tier_5", tiers.contains("EPIC") ? "1" : "0");
  }

  // *** For testing
  public void setCategory(final String category) {
    this.addFormField("category", category);
  }

  // *** For testing
  public void setTiers(final String tiers) {
    this.addFormField("consumable_tier_1", tiers.contains("crappy") ? "1" : "0");
    this.addFormField("consumable_tier_2", tiers.contains("decent") ? "1" : "0");
    this.addFormField("consumable_tier_3", tiers.contains("good") ? "1" : "0");
    this.addFormField("consumable_tier_4", tiers.contains("awesome") ? "1" : "0");
    this.addFormField("consumable_tier_5", tiers.contains("EPIC") ? "1" : "0");
  }

  // *** For testing
  public void setResponseTexts(String... responseTexts) {}

  @Override
  protected boolean retryOnTimeout() {
    return true;
  }

  public static final String getSearchString(String itemName) {
    int itemId = ItemDatabase.getItemId(itemName);

    if (itemId == -1) {
      return itemName;
    }

    String dataName = ItemDatabase.getItemDataName(itemId);
    int entityIndex = dataName.indexOf("&");

    if (entityIndex == -1) {
      return dataName;
    }

    return StringUtilities.getEntityDecode(dataName, false);
  }

  public List<PurchaseRequest> getResults() {
    return this.results;
  }

  public void setResults(final List<PurchaseRequest> results) {
    this.results = results;
  }

  // *** For testing
  public void setSearchString(final String searchString) {
    this.searchString = searchString;
    this.addFormField("pudnuggler", this.searchString);
  }

  // *** For testing
  public void setCheapestCount(final int cheapestCount) {
    this.addFormField("x_cheapest", String.valueOf(cheapestCount));
  }

  /**
   * Executes the search request. In the event that no item is found, the currently active frame
   * will be notified. Otherwise, all items are stored inside of the results list. Note also that
   * the results will be cleared before being stored.
   */

  // (Items 1-10 of 45)
  private static final Pattern ITERATION_PATTERN =
      Pattern.compile("\\(Items (\\d+)-(\\d+) of (\\d+)\\)");

  @Override
  public void run() {
    boolean items;
    if (this.searchString.length() == 0) {
      KoLmafia.updateDisplay("Scanning store inventories...");
      items = false;
    } else {
      // If only NPC items, no mall search needed
      if (!this.updateSearchString()) {
        return;
      }

      KoLmafia.updateDisplay("Searching for " + this.searchString + "...");
      items = true;
    }

    // We may need to iterate over multiple pages of search results
    this.removeFormField("start");

    String header = items ? "Searching for " + this.searchString : "Page";
    int page = 1;
    int limit = 0;

    while (true) {
      if (page > 1) {
        KoLmafia.updateDisplay(header + " (" + page + " of " + limit + ")...");
      }

      super.run();

      if (this.responseText == null || !KoLmafia.permitsContinue()) {
        return;
      }

      Matcher matcher = MallSearchRequest.ITERATION_PATTERN.matcher(this.responseText);
      if (!matcher.find()) {
        break;
      }

      int start = StringUtilities.parseInt(matcher.group(1));
      int end = StringUtilities.parseInt(matcher.group(2));
      int total = StringUtilities.parseInt(matcher.group(3));

      if (end >= total) {
        break;
      }

      if (limit == 0) {
        int size = (end - start) + 1;
        limit = (total + size - 1) / size;
      }

      this.addFormField("start", String.valueOf(end));

      page++;
    }

    this.maybeUpdateMallPrice();

    KoLmafia.updateDisplay("Search complete.");
  }

  // Public for access from tests.
  public void maybeUpdateMallPrice() {
    // If an exact match, we can think about updating mall_price().
    if (this.searchString.startsWith("\"") && this.results.size() > 0) {
      AdventureResult item = this.results.get(0).getItem();
      List<PurchaseRequest> search = new ArrayList<>(this.results);
      MallPriceManager.updateMallPrice(item, search);
      MallPriceManager.saveMallSearch(item.getItemId(), search);
    }
  }

  private boolean updateSearchString() {
    this.results.clear();

    boolean exact = this.searchString.startsWith("\"") && this.searchString.endsWith("\"");

    // If the search string is enclosed in "", the Item Matcher
    // will look for an exact match. Otherwise, it will do fuzzy
    // matching.

    List<String> itemNames = ItemDatabase.getMatchingNames(this.searchString);

    List<String> disambiguatedItemNames = new ArrayList<>();

    for (String itemName : itemNames) {
      int[] itemIds = ItemDatabase.getItemIds(itemName, 1, true);

      if (itemIds.length > 1) {
        for (int itemId : itemIds) {
          disambiguatedItemNames.add("[" + itemId + "]" + itemName);
        }
      } else {
        disambiguatedItemNames.add(itemName);
      }
    }

    // Check for any items which are not available in NPC stores and
    // known not to be tradeable to see if there's an exact match.

    Iterator<String> itemIterator = disambiguatedItemNames.iterator();
    int npcItemCount = 0;
    int untradeableCount = 0;

    while (itemIterator.hasNext()) {
      String itemName = itemIterator.next();
      int itemId = ItemDatabase.getItemId(itemName);
      boolean untradeable = !ItemDatabase.isTradeable(itemId);

      if (NPCStoreDatabase.contains(itemId) || CoinmastersDatabase.contains(itemId)) {
        npcItemCount++;
        if (untradeable) {
          untradeableCount++;
        }
      } else if (untradeable) {
        itemIterator.remove();
      }
    }

    int count = itemNames.size();

    if (count == 0) {
      // Assume the user knows what they want and allow an
      // unknown search for an exact match;
      return exact;
    }

    // If the results contain only untradeable NPC items, then you
    // don't need to run a mall search.

    if (count == untradeableCount) {
      this.finalizeList(itemNames);
      return false;
    }

    // If there is only one applicable match, then search for the
    // exact item (may be a fuzzy matched item).

    if (count == 1) {
      if (!exact) {
        this.searchString = "\"" + MallSearchRequest.getSearchString(itemNames.get(0)) + "\"";
      }
      this.addFormField("pudnuggler", this.searchString);
    }

    return true;
  }

  private static final Pattern FAVORITES_PATTERN =
      Pattern.compile("&action=unfave&whichstore=(\\d+)\">");

  private void searchFavoriteStores() {
    MallSearchRequest individualStore;
    Matcher storeMatcher = MallSearchRequest.FAVORITES_PATTERN.matcher(this.responseText);

    int lastFindIndex = 0;
    while (storeMatcher.find(lastFindIndex)) {
      lastFindIndex = storeMatcher.end();
      individualStore = new MallSearchRequest(StringUtilities.parseInt(storeMatcher.group(1)));
      individualStore.run();

      this.results.addAll(individualStore.results);
    }
  }

  private static final Pattern STOREID_PATTERN =
      Pattern.compile("<b style=\"color: [^\"]+\">(.*?) \\(<a.*?who=(\\d+)\"");
  private static final Pattern STOREPRICE_PATTERN =
      Pattern.compile("radio value=([\\d.]+).*?<b>(.*?)</b> \\(([\\d,]+)\\)(.*?)</td>");
  private static final Pattern STORELIMIT_PATTERN = Pattern.compile("Limit ([\\d,]+) /");
  private static final Pattern mangledEntityPattern = Pattern.compile("\\s+;");

  private void searchStore() {
    Matcher shopMatcher = MallSearchRequest.STOREID_PATTERN.matcher(this.responseText);
    if (!shopMatcher.find()) {
      return; // no mall store
    }

    int shopId = StringUtilities.parseInt(shopMatcher.group(2));

    // Handle character entities mangled by KoL.

    String shopName = mangledEntityPattern.matcher(shopMatcher.group(1)).replaceAll(";");

    int lastFindIndex = 0;
    Matcher priceMatcher = MallSearchRequest.STOREPRICE_PATTERN.matcher(this.responseText);

    while (priceMatcher.find(lastFindIndex)) {
      lastFindIndex = priceMatcher.end();
      String priceId = priceMatcher.group(1);

      String itemName = priceMatcher.group(2);

      int itemId = MallPurchaseRequest.itemFromStoreString(priceId);
      int quantity = StringUtilities.parseInt(priceMatcher.group(3));
      int limit = quantity;

      Matcher limitMatcher = MallSearchRequest.STORELIMIT_PATTERN.matcher(priceMatcher.group(4));
      if (limitMatcher.find()) {
        limit = StringUtilities.parseInt(limitMatcher.group(1));
      }

      long price = MallPurchaseRequest.priceFromStoreString(priceId);
      this.results.add(
          new MallPurchaseRequest(itemId, quantity, shopId, shopName, price, limit, true));
    }
  }

  private static final Pattern ITEMDETAIL_PATTERN =
      Pattern.compile(
          "<table class=\"itemtable\".*?item_(\\d+).*?descitem\\((\\d+)\\).*?<a[^>]*>(.*?)</a>(.*?)</table>",
          Pattern.DOTALL);
  private static final Pattern STOREDETAIL_PATTERN =
      Pattern.compile("<tr class=\"graybelow.+?</tr>", Pattern.DOTALL);
  private static final Pattern LISTQUANTITY_PATTERN = Pattern.compile("stock\">([\\d,]+)<");
  private static final Pattern LISTLIMIT_PATTERN =
      Pattern.compile("([\\d,]+)\\&nbsp;\\/\\&nbsp;day");
  private static final Pattern LISTDETAIL_PATTERN =
      Pattern.compile(
          "whichstore=(\\d+)\\&searchitem=(\\d+)\\&searchprice=(\\d+)\"><b>(.*?)</b>",
          Pattern.DOTALL);

  private void searchMall() {
    List<String> itemNames =
        this.searchString.length() == 0
            ? new ArrayList<>()
            : ItemDatabase.getMatchingNames(this.searchString);

    // Change all multi-line store names into single line store
    // names so that the parser doesn't get confused; remove all
    // stores where limits have already been reached (which have
    // been greyed out), and then remove all non-anchor tags to
    // make everything easy to parse.

    int startIndex = this.responseText.indexOf("Search Results:");
    String storeListResult = this.responseText.substring(Math.max(startIndex, 0));

    int previousItemId = -1;
    Matcher itemMatcher = MallSearchRequest.ITEMDETAIL_PATTERN.matcher(storeListResult);
    while (itemMatcher.find()) {
      int itemId = StringUtilities.parseInt(itemMatcher.group(1));
      String itemName = itemMatcher.group(3).trim();
      if (!itemName.equals(ItemDatabase.getItemDataName(itemId))) {
        String descId = itemMatcher.group(2);
        ItemDatabase.registerItem(itemId, itemName, descId);
      }

      String itemListResult = itemMatcher.group(4);
      Matcher linkMatcher = MallSearchRequest.STOREDETAIL_PATTERN.matcher(itemListResult);

      while (linkMatcher.find()) {
        String linkText = linkMatcher.group();
        Matcher quantityMatcher = MallSearchRequest.LISTQUANTITY_PATTERN.matcher(linkText);
        int quantity = 0;

        if (quantityMatcher.find()) {
          quantity = StringUtilities.parseInt(quantityMatcher.group(1));
        }

        int limit = quantity;
        boolean canPurchase = true;

        Matcher limitMatcher = MallSearchRequest.LISTLIMIT_PATTERN.matcher(linkText);
        if (limitMatcher.find()) {
          limit = StringUtilities.parseInt(limitMatcher.group(1));
          canPurchase = linkText.indexOf("graybelow limited") == -1;
        }

        // The next token contains data which identifies the shop
        // and the item (which will be used later), and the price!
        // which means you don't need to consult the next token.

        Matcher detailsMatcher = MallSearchRequest.LISTDETAIL_PATTERN.matcher(linkText);
        if (!detailsMatcher.find()) {
          continue;
        }

        int shopId = StringUtilities.parseInt(detailsMatcher.group(1));

        if (previousItemId != itemId) {
          previousItemId = itemId;
          this.addNPCStoreItem(itemId);
          this.addCoinMasterItem(itemId);
          // itemName is a data name
          // itemNames contains canonicalized names
          itemNames.remove(StringUtilities.getCanonicalName(itemName));
        }

        // Only add mall store results if the NPC store option
        // is not available.

        long price = StringUtilities.parseLong(detailsMatcher.group(3));
        String shopName = detailsMatcher.group(4).replaceAll("<br>", " ");

        this.results.add(
            new MallPurchaseRequest(itemId, quantity, shopId, shopName, price, limit, canPurchase));
      }
    }

    // Once the search is complete, add in any remaining NPC
    // store data and finalize the list.

    this.finalizeList(itemNames);
  }

  // For testing
  public List<Integer> extractShopIds(int itemId) {
    List<Integer> shopIds = new ArrayList<>();

    int startIndex = this.responseText.indexOf("Search Results:");
    String storeListResult = this.responseText.substring(Math.max(startIndex, 0));

    Matcher itemMatcher = MallSearchRequest.ITEMDETAIL_PATTERN.matcher(storeListResult);
    while (itemMatcher.find()) {
      int foundItemId = StringUtilities.parseInt(itemMatcher.group(1));
      if (foundItemId != itemId) {
        continue;
      }

      String itemListResult = itemMatcher.group(4);
      Matcher linkMatcher = MallSearchRequest.STOREDETAIL_PATTERN.matcher(itemListResult);

      while (linkMatcher.find()) {
        String linkText = linkMatcher.group();
        Matcher detailsMatcher = MallSearchRequest.LISTDETAIL_PATTERN.matcher(linkText);
        if (!detailsMatcher.find()) {
          continue;
        }

        int shopId = StringUtilities.parseInt(detailsMatcher.group(1));
        shopIds.add(shopId);
      }
    }

    return shopIds;
  }

  private void addNPCStoreItem(final int itemId) {
    if (NPCStoreDatabase.contains(itemId, false)) {
      var items = NPCStoreDatabase.getAvailablePurchaseRequests(itemId);
      this.results.addAll(items);
    }
  }

  private void addCoinMasterItem(final int itemId) {
    if (CoinmastersDatabase.contains(itemId, false)) {
      var items = CoinmastersDatabase.getAllPurchaseRequests(itemId);
      this.results.addAll(items);
    }
  }

  private void finalizeList(final List<String> itemNames) {
    // Now, for the items which matched, check to see if there are
    // any entries inside of the NPC store database for them and
    // add - this is just in case some of the items become notrade
    // so items can still be bought from the NPC stores.

    for (String itemName : itemNames) {
      int itemId = ItemDatabase.getItemId(itemName);
      this.addNPCStoreItem(itemId);
      this.addCoinMasterItem(itemId);
    }
  }

  @Override
  public void processResults() {
    if (this.storeId != 0) {
      this.searchStore();
    } else {
      this.searchMall();
    }
  }

  private static final Pattern NOBUYERS_PATTERN =
      Pattern.compile("<td valign=\"center\" class=\"buyers\">&nbsp;</td>");

  public static void decorateMallSearch(StringBuffer buffer) {
    decorateMallSearchAddBuyButtons(buffer);
    decorateMallSearchHighlightStores(buffer);
  }

  public static void decorateMallSearchAddBuyButtons(StringBuffer buffer) {
    Matcher matcher = MallSearchRequest.STOREDETAIL_PATTERN.matcher(buffer);
    while (matcher.find()) {
      String store = matcher.group(0);
      Matcher nobuyersMatcher = MallSearchRequest.NOBUYERS_PATTERN.matcher(store);
      if (!nobuyersMatcher.find()) {
        // Good store which does not disable buying from search results
        continue;
      }

      // Bad store which disables buying from the search results.

      Matcher detailsMatcher = MallSearchRequest.LISTDETAIL_PATTERN.matcher(store);
      if (!detailsMatcher.find()) {
        continue;
      }

      String whichstore = detailsMatcher.group(1);
      String searchitem = detailsMatcher.group(2);
      int itemId = StringUtilities.parseInt(searchitem);
      String searchprice = detailsMatcher.group(3);
      long price = StringUtilities.parseLong(searchprice);

      // Replace:
      //   <td valign="center" class="buyers">&nbsp;</td>
      // with:
      //   <td valign="center" class="buyers">[<a
      // href="mallstore.php?buying=1&quantity=1&whichitem=3980000004455&ajax=1&pwd&whichstore=102069" class="buyone">buy</a>]&nbsp;[<a href="#" rel="mallstore.php?buying=1&whichitem=3980000004455&ajax=1&pwd&whichstore=102069&quantity=" class="buysome">buy&nbsp;some</a>]</td>

      String storeString = MallPurchaseRequest.getStoreString(itemId, price);

      String buyers =
          "<td valign=\"center\" class=\"buyers\">"
              + "[<a href=\"mallstore.php?buying=1&quantity=1&whichitem="
              + storeString
              + "&ajax=1&pwd="
              + GenericRequest.passwordHash
              + "&whichstore="
              + whichstore
              + "\" class=\"buyone\">buy</a>]"
              + "&nbsp;"
              + "[<a href=\"#\" rel =\"mallstore.php?buying=1&whichitem="
              + storeString
              + "&ajax=1&pwd="
              + GenericRequest.passwordHash
              + "&whichstore="
              + whichstore
              + "&quantity=\" class=\"buysome\">buy&nbsp;some</a>]"
              + "</td>";
      buffer.replace(
          matcher.start() + nobuyersMatcher.start(),
          matcher.start() + nobuyersMatcher.end(),
          buyers);
    }
  }

  public static void decorateMallSearchHighlightStores(StringBuffer buffer) {
    Set<Integer> forbidden = MallPurchaseRequest.getForbiddenStores();

    Matcher matcher = MallSearchRequest.STOREDETAIL_PATTERN.matcher(buffer);

    while (matcher.find()) {
      String store = matcher.group(0);

      Matcher detailsMatcher = MallSearchRequest.LISTDETAIL_PATTERN.matcher(store);
      if (!detailsMatcher.find()) {
        continue;
      }

      String whichstore = detailsMatcher.group(1);
      int storeId = StringUtilities.parseInt(whichstore);

      // If the store is in the forbidden list
      if (forbidden.contains(storeId)) {
        // Add a red gradient background and a title attribute to explain.
        store =
            store.replaceFirst(
                ">",
                " style=\"background-image:linear-gradient(to right, rgba(255,0,0,0), pink);\" title=\"The preference 'forbiddenStores' "
                    + "contains this store.\">");

        buffer.replace(matcher.start(), matcher.end(), store);
        continue;
      }

      if (KoLCharacter.getUserId() == storeId) {
        // Add a blue gradient background and a title attribute to explain.
        store =
            store.replaceFirst(
                ">",
                " style=\"background-image:linear-gradient(to right, rgba(0,0,255,0), lightblue);\" title=\"This is your store.\">");

        buffer.replace(matcher.start(), matcher.end(), store);
      }
    }
  }

  private static String tierName(int tier) {
    return switch (tier) {
      case 1 -> "crappy";
      case 2 -> "decent";
      case 3 -> "good";
      case 4 -> "awesome";
      case 5 -> "EPIC";
      default -> "???";
    };
  }

  private static String extractTiers(String urlString) {
    StringBuilder tiers = new StringBuilder();
    for (int i = 1; i <= 5; ++i) {
      String name = "consumable_tier_" + i;
      String field = GenericRequest.extractValueOrDefault(urlString, name, "0");
      if (!field.equals("0")) {
        tiers.append(tiers.length() == 0 ? "[" : ", ");
        tiers.append(tierName(i));
      }
    }
    if (tiers.length() > 0) {
      tiers.append("]");
    }
    return tiers.toString();
  }

  public static boolean registerRequest(final String urlString) {

    // mallstore.php?whichstore=294980
    // Without buying=1 or buying=Yep., this is a search, not a purchase
    if (urlString.startsWith("mallstore.php")) {
      // It's a purchase. Defer to MallPurchaseRequest
      if (urlString.contains("buying=1") || urlString.contains("buying=Yep.")) {
        return false;
      }

      int shopId = MallPurchaseRequest.getStoreId(urlString);
      String storeName = shopId != -1 ? ("shop #" + shopId) : "a PC store";

      String message = "mallsearch " + storeName;
      RequestLogger.updateSessionLog(message);
      return true;
    }

    if (!urlString.startsWith("mall.php")) {
      return false;
    }

    StringBuilder message = new StringBuilder();
    message.append("mallsearch ");

    String searchString =
        GenericRequest.decodeField(GenericRequest.extractValueOrDefault(urlString, "pudnuggler"));
    String category = GenericRequest.extractValueOrDefault(urlString, "category");
    String start = GenericRequest.extractValueOrDefault(urlString, "start");
    int page = start.equals("") ? 1 : (Integer.parseInt(start) / 30 + 1);

    if (searchString.equals("")) {
      message.append("category ");
      message.append(category);
    } else {
      message.append(searchString);
    }

    String tiers = MallSearchRequest.extractTiers(urlString);
    if (!tiers.equals("")) {
      message.append(" ");
      message.append(tiers);
    }

    if (page > 1) {
      message.append(" (page ");
      message.append(page);
      message.append(")");
    }

    RequestLogger.updateSessionLog(message.toString());

    return true;
  }
}
