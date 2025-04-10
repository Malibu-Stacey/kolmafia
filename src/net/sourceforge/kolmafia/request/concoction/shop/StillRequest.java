package net.sourceforge.kolmafia.request.concoction.shop;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sourceforge.kolmafia.KoLCharacter;
import net.sourceforge.kolmafia.KoLConstants;
import net.sourceforge.kolmafia.KoLmafia;
import net.sourceforge.kolmafia.objectpool.Concoction;
import net.sourceforge.kolmafia.objectpool.ConcoctionPool;
import net.sourceforge.kolmafia.request.concoction.CreateItemRequest;
import net.sourceforge.kolmafia.shop.ShopRequest;
import net.sourceforge.kolmafia.utilities.StringUtilities;

public class StillRequest extends CreateItemRequest {
  public static final String SHOPID = "still";

  public StillRequest(final Concoction conc) {
    super("shop.php", conc);

    this.addFormField("whichshop", SHOPID);
    this.addFormField("action", "buyitem");
    int row = ConcoctionPool.idToRow(this.getItemId());
    this.addFormField("whichrow", String.valueOf(row));
  }

  @Override
  public void run() {
    // Attempt to retrieve the ingredients
    if (!this.makeIngredients()) {
      return;
    }

    KoLmafia.updateDisplay("Creating " + this.getQuantityNeeded() + " " + this.getName() + "...");
    this.addFormField("quantity", String.valueOf(this.getQuantityNeeded()));
    super.run();
  }

  @Override
  public void processResults() {
    String urlString = this.getURLString();
    String responseText = this.responseText;

    if (urlString.contains("action=buyitem") && !responseText.contains("You acquire")) {
      KoLmafia.updateDisplay(KoLConstants.MafiaState.ERROR, "Still upgrading was unsuccessful.");
      return;
    }

    ShopRequest.parseResponse(urlString, responseText);
  }

  private static final Pattern STILLS_PATTERN = Pattern.compile("with (\\d+) bright");

  public static void parseResponse(final String urlString, final String responseText) {
    Matcher matcher = StillRequest.STILLS_PATTERN.matcher(responseText);
    int count = matcher.find() ? StringUtilities.parseInt(matcher.group(1)) : 0;
    KoLCharacter.setStillsAvailable(count);
  }
}
