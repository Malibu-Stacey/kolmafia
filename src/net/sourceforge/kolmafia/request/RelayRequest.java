package net.sourceforge.kolmafia.request;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.java.dev.spellcast.utilities.DataUtilities;
import net.sourceforge.kolmafia.AdventureResult;
import net.sourceforge.kolmafia.AreaCombatData;
import net.sourceforge.kolmafia.CoinmasterData;
import net.sourceforge.kolmafia.FamiliarData;
import net.sourceforge.kolmafia.KoLAdventure;
import net.sourceforge.kolmafia.KoLCharacter;
import net.sourceforge.kolmafia.KoLConstants;
import net.sourceforge.kolmafia.KoLConstants.ConsumptionType;
import net.sourceforge.kolmafia.KoLmafia;
import net.sourceforge.kolmafia.KoLmafiaASH;
import net.sourceforge.kolmafia.ModifierType;
import net.sourceforge.kolmafia.RequestEditorKit;
import net.sourceforge.kolmafia.SpecialOutfit;
import net.sourceforge.kolmafia.StaticEntity;
import net.sourceforge.kolmafia.chat.ChatFormatter;
import net.sourceforge.kolmafia.chat.ChatManager;
import net.sourceforge.kolmafia.chat.ChatMessage;
import net.sourceforge.kolmafia.chat.ChatPoller;
import net.sourceforge.kolmafia.chat.ChatSender;
import net.sourceforge.kolmafia.chat.HistoryEntry;
import net.sourceforge.kolmafia.chat.SentMessageEntry;
import net.sourceforge.kolmafia.equipment.Slot;
import net.sourceforge.kolmafia.modifiers.Lookup;
import net.sourceforge.kolmafia.modifiers.ModifierList;
import net.sourceforge.kolmafia.moods.MoodManager;
import net.sourceforge.kolmafia.moods.RecoveryManager;
import net.sourceforge.kolmafia.objectpool.AdventurePool;
import net.sourceforge.kolmafia.objectpool.Concoction;
import net.sourceforge.kolmafia.objectpool.ConcoctionPool;
import net.sourceforge.kolmafia.objectpool.EffectPool;
import net.sourceforge.kolmafia.objectpool.FamiliarPool;
import net.sourceforge.kolmafia.objectpool.ItemPool;
import net.sourceforge.kolmafia.objectpool.OutfitPool;
import net.sourceforge.kolmafia.persistence.AdventureDatabase;
import net.sourceforge.kolmafia.persistence.AdventureSpentDatabase;
import net.sourceforge.kolmafia.persistence.EffectDatabase;
import net.sourceforge.kolmafia.persistence.EquipmentDatabase;
import net.sourceforge.kolmafia.persistence.FamiliarDatabase;
import net.sourceforge.kolmafia.persistence.ItemDatabase;
import net.sourceforge.kolmafia.persistence.ModifierDatabase;
import net.sourceforge.kolmafia.persistence.NPCStoreDatabase;
import net.sourceforge.kolmafia.persistence.QuestDatabase;
import net.sourceforge.kolmafia.persistence.QuestDatabase.Quest;
import net.sourceforge.kolmafia.persistence.SkillDatabase;
import net.sourceforge.kolmafia.persistence.SkillDatabase.Category;
import net.sourceforge.kolmafia.preferences.Preferences;
import net.sourceforge.kolmafia.request.ResearchBenchRequest.Research;
import net.sourceforge.kolmafia.request.coinmaster.DimemasterRequest;
import net.sourceforge.kolmafia.request.coinmaster.QuartersmasterRequest;
import net.sourceforge.kolmafia.session.ChoiceManager;
import net.sourceforge.kolmafia.session.EquipmentManager;
import net.sourceforge.kolmafia.session.EquipmentRequirement;
import net.sourceforge.kolmafia.session.InventoryManager;
import net.sourceforge.kolmafia.session.IslandManager;
import net.sourceforge.kolmafia.session.LightsOutManager;
import net.sourceforge.kolmafia.session.SorceressLairManager;
import net.sourceforge.kolmafia.session.TavernManager;
import net.sourceforge.kolmafia.session.TurnCounter;
import net.sourceforge.kolmafia.session.VoteMonsterManager;
import net.sourceforge.kolmafia.swingui.AdventureFrame;
import net.sourceforge.kolmafia.swingui.CommandDisplayFrame;
import net.sourceforge.kolmafia.textui.AshRuntime;
import net.sourceforge.kolmafia.textui.RuntimeLibrary;
import net.sourceforge.kolmafia.textui.javascript.JSONValueConverter;
import net.sourceforge.kolmafia.textui.javascript.ValueConverter;
import net.sourceforge.kolmafia.utilities.ByteBufferUtilities;
import net.sourceforge.kolmafia.utilities.FileUtilities;
import net.sourceforge.kolmafia.utilities.PauseObject;
import net.sourceforge.kolmafia.utilities.StringUtilities;
import net.sourceforge.kolmafia.utilities.WikiUtilities;
import net.sourceforge.kolmafia.webui.RelayServer;
import net.sourceforge.kolmafia.webui.StationaryButtonDecorator;

public class RelayRequest extends PasswordHashRequest {
  private final PauseObject pauser = new PauseObject();

  private static final HashMap<String, File> overrideMap = new HashMap<>();
  private static final Object lock = new Object(); // used to synch

  private static final Pattern HTTP_STATUS_PATTERN = Pattern.compile("^HTTP/1.1 ([0-9]+)");

  private static final Pattern STORE_PATTERN =
      Pattern.compile(
          "<tr><td><input name=whichitem type=radio value=([\\d.]+).*?</tr>", Pattern.DOTALL);

  private static final Pattern BASE_LINK_PATTERN =
      Pattern.compile("([\\.\\s])(src|href|action)(=[\"']?)([^\\/\"'][^\\s\"'>]+)");
  private static final Pattern CONFIRMATION_PATTERN = Pattern.compile("&confirm[\\d]+=on");

  private static KoLAdventure lastSafety = null;

  // If allowOverride is true, when we run this request, we will execute a
  // relay script, if present, to handle the request to KoL.
  private boolean allowOverride;

  public List<String> headers = new ArrayList<>();
  public Set<ServerCookie> serverCookies = null;
  public String cookies = null;
  public byte[] rawByteBuffer = null;
  public String contentType = null;
  public long lastModified = 0;
  public String statusLine = "HTTP/1.1 302 Found";

  public static boolean specialCommandIsAdventure = false;
  public static String specialCommandResponse = "";
  public static String specialCommandStatus = "";
  public static String redirectedCommandURL = "";

  public enum Confirm {
    COUNTER,
    MCD,
    FAMILIAR,
    RECOVERY,
    SORCERESS,
    WOSSNAME,
    TOKENS,
    SEAL,
    ARCADE,
    KUNGFU,
    POOL_SKILL,
    WINEGLASS,
    COLOSSEUM,
    GREMLINS,
    HARDCOREPVP,
    DESERT_UNHYDRATED,
    MOHAWK_WIG,
    CELLAR,
    CELLAR2,
    CELLAR3,
    BOILER,
    BOILER2,
    BOILER3,
    DIARY,
    BORING_DOORS,
    SPELUNKY,
    ZEPPELIN,
    OVERDRUNK_ADVENTURE,
    STICKER,
    DESERT_OFFHAND,
    MACHETE,
    RALPH,
    RALPH1,
    RALPH2,
    DESERT_WEAPON,
    SPRING_SHOES,
    TRANSFORM;

    @Override
    public String toString() {
      return "confirm" + ordinal();
    }
  }

  private static boolean ignoreBoringDoorsWarning = false;
  public static boolean ignoreDesertWarning = false;
  public static boolean ignoreDesertWeaponWarning = false;
  private static boolean ignoreDesertOffhandWarning = false;
  public static boolean ignoreMacheteWarning = false;
  public static boolean ignoreMohawkWigWarning = false;
  private static boolean ignorePoolSkillWarning = false;
  private static boolean ignoreFullnessWarning = false;

  // For testing
  public String lastWarning = "";

  public static final void reset() {
    RelayRequest.specialCommandIsAdventure = false;
    RelayRequest.specialCommandResponse = "";
    RelayRequest.specialCommandStatus = "";
    RelayRequest.redirectedCommandURL = "";
    RelayRequest.ignoreBoringDoorsWarning = false;
    RelayRequest.ignoreDesertWarning = false;
    RelayRequest.ignoreDesertWeaponWarning = false;
    RelayRequest.ignoreDesertOffhandWarning = false;
    RelayRequest.ignoreMacheteWarning = false;
    RelayRequest.ignoreMohawkWigWarning = false;
    RelayRequest.ignorePoolSkillWarning = false;
    RelayRequest.ignoreFullnessWarning = false;
  }

  public RelayRequest(final boolean allowOverride) {
    super("");
    this.allowOverride = allowOverride;
  }

  public void setAllowOverride(final boolean allowOverride) {
    this.allowOverride = allowOverride;
  }

  @Override
  public String getHashField() {
    // Do not automatically include the password hash on requests
    // relayed from the browser.
    return null;
  }

  public static String removeConfirmationFields(String adventureURL) {
    return CONFIRMATION_PATTERN.matcher(adventureURL).replaceAll("");
  }

  @Override
  public GenericRequest constructURLString(
      final String newURLString, final boolean usePostMethod, final boolean encoded) {
    super.constructURLString(newURLString, usePostMethod, encoded);

    this.rawByteBuffer = null;
    this.headers.clear();

    String path = this.getBasePath();
    String relayField = this.getFormField("relay");

    if (path.endsWith(".css")) {
      this.contentType = "text/css";
    } else if (path.endsWith(".js")) {
      // Support JS-driven relay scripts
      if (relayField != null && relayField.equals("true")) {
        this.contentType = "text/html";
      } else {
        this.contentType = "text/javascript";
      }
    } else if (path.endsWith(".gif")) {
      this.contentType = "image/gif";
    } else if (path.endsWith(".png")) {
      this.contentType = "image/png";
    } else if (path.endsWith(".jpg") || path.endsWith(".jpeg")) {
      this.contentType = "image/jpeg";
    } else if (path.endsWith(".ico")) {
      this.contentType = "image/x-icon";
    } else if (path.endsWith(".mp3")) {
      this.contentType = "audio/mpeg";
    } else if (path.matches(".*\\.(php|html|ash)")) {
      this.contentType = "text/html";
    } else {
      this.contentType = "text/plain";
    }

    return this;
  }

  private static boolean isJunkItem(final int itemId, final long price) {
    if (!Preferences.getBoolean("relayHidesJunkMallItems")) {
      return false;
    }

    if (price > KoLCharacter.getAvailableMeat()) {
      return true;
    }

    if (NPCStoreDatabase.contains(itemId)) {
      if (price == 100 || price > Math.abs(ItemDatabase.getPriceById(itemId)) * 2L) {
        return true;
      }
    }

    for (int i = 0; i < KoLConstants.junkList.size(); ++i) {
      if (KoLConstants.junkList.get(i).getItemId() == itemId) {
        return true;
      }
    }

    return false;
  }

  @Override
  protected boolean retryOnTimeout() {
    return false;
  }

  @Override
  protected boolean shouldSuppressUpdate() {
    return true;
  }

  private void parseCookies() {
    // Cookie:
    // AWSALB=iKPiF7/vFUCL2z+/EsRTv6hJcVCELL/BskHXIkr6V2SymH+NlxEU28mSmSPUWnVY/Oyw4+EFLWA2tzw6w126Bk//oKrbpBa+wCn4ije528LSM2wLUpyCHglCstyS

    String[] cookies = this.getHeaderField("Cookie").split("; *");
    for (String cookie : cookies) {
      int equals = cookie.indexOf("=");
      if (equals != -1) {
        if (GenericRequest.specialCookie(cookie.substring(0, equals))) {
          continue;
        }

        ServerCookie serverCookie = new ServerCookie(cookie);
        if (this.serverCookies == null) {
          this.serverCookies = new LinkedHashSet<>();
        } else {
          this.serverCookies.remove(serverCookie);
        }
        this.serverCookies.add(serverCookie);
      }
    }

    if (this.serverCookies == null) {
      return;
    }

    StringBuilder buffer = new StringBuilder();
    for (ServerCookie cookie : this.serverCookies) {
      if (buffer.length() == 0) {
        buffer.append("; ");
      }
      buffer.append(cookie.toString());
    }
    this.cookies = buffer.toString();
  }

  @Override
  public StringBuilder getCookies(final StringBuilder cookies) {
    if (this.cookies != null) {
      cookies.append(this.cookies);
      cookies.append("; ");
    }
    return super.getCookies(cookies);
  }

  @Override
  public void formatResponse() {
    this.statusLine = "HTTP/1.1 200 OK";
    String path = this.getBasePath();

    // Use previously decorated response for fights and choices if available
    boolean decorate = false;
    String text = null;

    if (path.startsWith("fight.php")) {
      text = FightRequest.lastDecoratedResponseText;
    } else if (path.startsWith("choice.php")) {
      text = ChoiceManager.lastDecoratedResponseText;
    }

    if (text == null) {
      text = this.responseText;
      decorate = true;
    }

    StringBuffer responseBuffer = new StringBuffer(text);

    // Fix KoLmafia getting outdated by events happening
    // in the browser by using the sidepane.

    if (path.equals("chatlaunch.php")) {
      if (Preferences.getBoolean("relayAddsGraphicalCLI")) {
        int linkIndex = responseBuffer.indexOf("<a href");
        if (linkIndex != -1) {
          responseBuffer.insert(
              linkIndex,
              "<a href=\""
                  + KoLConstants.CLI_HTML
                  + "\"><b>KoLmafia gCLI</b></a></center><p>Type KoLmafia scripting commands in your browser!</p><center>");
        }
      }

      if (Preferences.getBoolean("relayUsesIntegratedChat")) {
        StringUtilities.singleStringReplace(responseBuffer, "lchat.php", KoLConstants.CHAT_HTML);
      }
    }

    // If this is a store, you can opt to remove all the min-priced
    // items from view along with all the items which are priced
    // above affordable levels.

    else if (path.startsWith("mallstore.php")) {
      int searchItemId;
      int searchPrice;

      searchItemId = StringUtilities.parseInt(this.getFormField("searchitem"));
      searchPrice = StringUtilities.parseInt(this.getFormField("searchprice"));

      Matcher itemMatcher = RelayRequest.STORE_PATTERN.matcher(this.responseText);

      while (itemMatcher.find()) {
        String itemData = itemMatcher.group(1);

        int itemId = MallPurchaseRequest.itemFromStoreString(itemData);
        long price = MallPurchaseRequest.priceFromStoreString(itemData);

        if (itemId != searchItemId && RelayRequest.isJunkItem(itemId, price)) {
          StringUtilities.singleStringDelete(responseBuffer, itemMatcher.group());
        }
      }

      // Also make sure the item that the person selected
      // when coming into the store is pre-selected.

      if (searchItemId != -1) {
        String searchString = MallPurchaseRequest.getStoreString(searchItemId, searchPrice);
        StringUtilities.singleStringReplace(
            responseBuffer, "value=" + searchString, "checked value=" + searchString);
      }
    } else if (path.startsWith("fight.php")) {
      String action = this.getFormField("action");
      if (action != null && action.equals("skill")) {
        String skillId = this.getFormField("whichskill");
        Category category = SkillDatabase.getSkillCategory(StringUtilities.parseInt(skillId));
        if (category != Category.CONDITIONAL) {
          StationaryButtonDecorator.addSkillButton(skillId);
        }
      }
    }

    // Fights and choices are already decorated.
    if (decorate) {
      try {
        RequestEditorKit.getFeatureRichHTML(this.getURLString(), responseBuffer);
      } catch (Exception e) {
        StaticEntity.printStackTrace(e);
      }
    }

    // Remove the default frame busting script so that
    // we can detach user interface elements.

    StringUtilities.singleStringReplace(
        responseBuffer, "frames.length == 0", "frames.length == -1");
    StringUtilities.globalStringReplace(responseBuffer, " name=adv ", " name=snarfblat ");

    this.responseText = responseBuffer.toString();
  }

  public void printHeaders(final PrintStream ostream) {
    if (!this.headers.isEmpty()) {
      // This is a "pseudoResponse" generated by KoLmafia itself.
      // Send down any headers we generated.
      for (String header : this.headers) {
        ostream.print(header);
        ostream.print("\r\n");
      }
      return;
    }

    if (this.response != null) {
      // This is a response from KoL.
      // Send down any headers KoL generated

      Map<String, List<String>> headerFields = this.response.headers().map();

      for (Entry<String, List<String>> entry : headerFields.entrySet()) {
        String key = entry.getKey();
        if (key == null) {
          continue;
        }

        // ignore psuedo-headers
        if (key.startsWith(":")) {
          continue;
        }

        // KoL is known to send back CONTENT-LENGTH headers.
        // Since there is no built-in startsWithIgnoreCase,
        // upper-case the key when using startsWith.
        // Just in case, use this key even when using equals
        String ukey = key.toUpperCase();

        // We redo the Content-Type and Content-Length encodings, and ignore Content-Encoding.
        if (ukey.startsWith("CONTENT")) {
          continue;
        }

        // Suppress certain other KoL server headers
        // that we do not emulate
        if (ukey.equals("TRANSFER-ENCODING") || ukey.equals("CONNECTION")) {
          continue;
        }

        for (String value : entry.getValue()) {
          if (ukey.equals("SET-COOKIE")) {
            value = GenericRequest.mungeCookieDomain(value);
          }
          if (ukey.equals("CACHE-CONTROL")) {
            if (Preferences.getBoolean("relayCacheUncacheable")) {
              value = value.replaceFirst("no-store(, )?", "");
            }
          }

          ostream.print(key);
          ostream.print(": ");
          ostream.print(value);
          ostream.print("\r\n");
        }
      }

      if (this.responseCode == 200 && this.rawByteBuffer != null) {
        ostream.print("Content-Type: ");
        var contentType =
            this.response.headers().firstValue("Content-Type").orElse(this.contentType);
        ostream.print(contentType);

        if (contentType.startsWith("text") && !contentType.contains(";")) {
          ostream.print("; charset=UTF-8");
        }

        ostream.print("\r\n");

        ostream.print("Content-Length: ");
        ostream.print(this.rawByteBuffer.length);
        ostream.print("\r\n");
      }
    }
  }

  public String getRedirectLocation() {
    if (this.responseCode != 302) {
      return "";
    }

    return this.getHeaderField("Location");
  }

  public String getHeaderField(final String field) {
    if (!this.headers.isEmpty()) {
      // This is a "pseudoResponse" generated by KoLmafia itself.
      for (String header : this.headers) {
        if (header.startsWith(field)) {
          return header.substring(field.length() + 2);
        }
      }
      return "";
    }

    if (this.response != null) {
      // This is a response from KoL.
      return this.response.headers().firstValue(field).orElse(null);
    }

    return "";
  }

  public void pseudoResponse(final String status, final String responseText) {
    this.statusLine = status;

    this.headers.add("Date: " + StringUtilities.formatDate(new Date()));
    this.headers.add("Server: " + StaticEntity.getVersion());

    Matcher m = HTTP_STATUS_PATTERN.matcher(status);
    if (m.find()) {
      this.responseCode = Integer.parseInt(m.group(1));
    }

    if (this.responseCode == 302) {
      this.headers.add("Location: " + responseText);
      this.responseText = "";
    } else if (this.responseCode == 200) {
      if (responseText == null || responseText.length() == 0) {
        this.responseText = " ";
      } else {
        this.rawByteBuffer = null;
        this.responseText = responseText;
      }

      if (this.contentType.equals("text/html")) {
        this.headers.add("Content-Type: text/html; charset=UTF-8");
        this.headers.add("Cache-Control: no-cache, must-revalidate");
        this.headers.add("Pragma: no-cache");
      } else {
        this.headers.add("Content-Type: " + this.contentType);
      }

      if (this.lastModified > 0) {
        String lastModified = StringUtilities.formatDate(this.lastModified);
        if (!lastModified.equals("")) {
          this.headers.add("Last-Modified: " + lastModified);
        }
      }
    } else {
      this.responseText = " ";
    }
  }

  private StringBuffer readContents(final BufferedReader reader) {
    StringBuffer contentBuffer = new StringBuffer();
    if (reader == null) {
      return contentBuffer;
    }

    try (reader) {
      String line;
      while ((line = reader.readLine()) != null) {
        contentBuffer.append(line);
        contentBuffer.append(KoLConstants.LINE_BREAK);
      }
    } catch (IOException e) {
    }

    return contentBuffer;
  }

  private static final String[] IMAGES =
      new String[] {
        "adventureimages/hellion.gif", "itemimages/goldmonkey.gif",
      };

  private static final String OVERRIDE_DIRECTORY = "images/overrides/";

  public static final void loadOverrideImages(final boolean enabled) {
    for (String filename : IMAGES) {
      String path = "images/" + filename;
      File cachedFile = new File(KoLConstants.ROOT_LOCATION, path);
      cachedFile.delete();
      if (enabled) {
        int index = path.lastIndexOf("/");
        File parent = new File(KoLConstants.ROOT_LOCATION, path.substring(0, index));
        String localname = path.substring(index + 1);
        FileUtilities.loadLibrary(parent, RelayRequest.OVERRIDE_DIRECTORY, localname);
      }
    }
  }

  public static final void overrideImages(final StringBuffer buffer) {
    if (!Preferences.getBoolean("relayOverridesImages")) {
      return;
    }

    for (String filename : IMAGES) {
      String replace = "/images/" + filename;
      for (String path : KoLmafia.IMAGE_SERVER_PATHS) {
        String find = path + filename;
        StringUtilities.globalStringReplace(buffer, find, replace);
      }
    }
  }

  private static final FilenameFilter RELAYIMAGES_FILTER =
      new FilenameFilter() {
        @Override
        public boolean accept(final File dir, final String name) {
          return !name.equals("relayimages");
        }
      };

  private static void clearImageDirectory(File directory, FilenameFilter filter) {
    File[] files = directory.listFiles(filter);
    if (files != null) {
      for (File file : files) {
        if (file.isDirectory()) {
          RelayRequest.clearImageDirectory(file, null);
        }

        file.delete();
      }
    }
  }

  public static void clearImageCache() {
    RelayRequest.clearImageDirectory(KoLConstants.IMAGE_LOCATION, RELAYIMAGES_FILTER);
    Preferences.setLong("lastImageCacheClear", (new Date()).getTime());
  }

  private static String localImagePath(final String filename) {
    return filename.endsWith("favicon.ico")
        ? "https://www.kingdomofloathing.com/favicon.ico"
        : filename.startsWith("images")
            ? KoLmafia.imageServerPrefix() + filename.substring(6)
            : filename.startsWith("iii")
                ? KoLmafia.imageServerPrefix() + filename.substring(3)
                : filename;
  }

  public static File findLocalImage(final String filename) {
    return FileUtilities.imageFile(RelayRequest.localImagePath(filename));
  }

  private void sendLocalImage(final String filename) {
    File imageFile = FileUtilities.downloadImage(RelayRequest.localImagePath(filename));

    if (imageFile == null) {
      this.sendNotFound();
      return;
    }

    this.lastModified = imageFile.lastModified();
    this.rawByteBuffer = ByteBufferUtilities.read(imageFile);
    this.headers.add("Access-Control-Allow-Origin: *");
    this.pseudoResponse("HTTP/1.1 200 OK", "");
  }

  public static File findRelayFile(final String filename) {
    if ((filename == null) || (filename.contains(".."))) {
      return null;
    } else {
      return new File(KoLConstants.RELAY_LOCATION, filename);
    }
  }

  private void setLastModified(final File override) {
    long lastModified = override.lastModified();
    long now = (new Date()).getTime();
    long expires = now + (1000L * 60 * 60 * 24 * 30);
    this.headers.add("Last-Modified: " + StringUtilities.formatDate(lastModified));
    this.headers.add("Expires: " + StringUtilities.formatDate(expires));
  }

  private void sendLocalFile(final String filename) {
    if (!RelayRequest.overrideMap.containsKey(filename)) {
      RelayRequest.overrideMap.put(filename, RelayRequest.findRelayFile(filename));
    }

    File override = RelayRequest.overrideMap.get(filename);
    if (override == null) {
      this.sendNotFound();
      return;
    }

    // Make sure that the file is actually in the relay directory
    try {
      String overridePath = override.getCanonicalPath();
      String relayPath = KoLConstants.RELAY_LOCATION.getCanonicalPath();

      if (!overridePath.startsWith(relayPath)) {
        this.sendNotFound();
        return;
      }
    } catch (IOException e) {
    }

    // If it's a binary file, send it back without loading it as a string.
    if (!this.contentType.startsWith("text/") && !this.contentType.equals("application/json")) {
      this.rawByteBuffer = ByteBufferUtilities.read(override);
      if (this.rawByteBuffer.length == 0) {
        this.sendNotFound();
        return;
      }
      this.statusLine = "HTTP/1.1 200 OK";
      this.responseCode = 200;
      this.setLastModified(override);
      return;
    }

    // Read the file
    StringBuffer replyBuffer;

    if (override.exists()) {
      // If the file is in the file system, it is a local override
      replyBuffer = this.readContents(DataUtilities.getReader(override));
    } else {
      // If the file is not in the file system, it's probably a KoL
      // file which is not in the image directory for some reason.
      // Download it from KoL.
      replyBuffer = FileUtilities.downloadFile("https://www.kingdomofloathing.com/" + filename);
    }

    // If it is a KoLmafia built-in file, as opposed to the
    // user-supplied relay script, do special things
    if (RelayRequest.builtinRelayFile(filename)) {
      if (replyBuffer.indexOf("MAFIAHIT") != -1) {
        StringUtilities.globalStringReplace(
            replyBuffer, "MAFIAHIT", "pwd=" + GenericRequest.passwordHash);
      } else if (!filename.endsWith(".html")) {
        setLastModified(override);
      }
    }

    if (this.isChatRequest) {
      StringUtilities.globalStringReplace(replyBuffer, "<br>", "</font><br>");
    }

    if (filename.endsWith("chat.html")) {
      RequestEditorKit.addChatFeatures(replyBuffer);
    }

    // Return the reply buffer as the response text to the local request
    this.pseudoResponse("HTTP/1.1 200 OK", replyBuffer.toString());
  }

  public static boolean builtinRelayFile(final String file) {
    for (int i = 0; i < KoLConstants.RELAY_FILES.length; ++i) {
      if (file.equals(KoLConstants.RELAY_FILES[i])) {
        return true;
      }
    }
    return false;
  }

  public void insertAfterEnd(final StringBuffer replyBuffer, final String contents) {
    int terminalIndex = replyBuffer.lastIndexOf("</html>");
    if (terminalIndex == -1) {
      replyBuffer.append(contents);
      replyBuffer.append("</html>");
    } else {
      replyBuffer.insert(terminalIndex, contents);
    }
  }

  public void sendNotFound() {
    this.pseudoResponse("HTTP/1.1 404 Not Found", "");
    this.responseCode = 404;
  }

  private boolean sendBattlefieldWarning(final String urlString, final KoLAdventure adventure) {
    // If visiting big island, see if trying to enter a camp
    if (urlString.startsWith("bigisland.php")) {
      return checkCampVisit(urlString);
    }

    if (adventure == null) {
      return false;
    }

    int location = adventure.getAdventureNumber();

    // If he's not going on to the battlefield, no problem
    if (location != AdventurePool.FRAT_UNIFORM_BATTLEFIELD
        && location != AdventurePool.HIPPY_UNIFORM_BATTLEFIELD) {
      return false;
    }

    // You can't tell which uniform is being worn from the URL;
    // the player can adventure in one uniform, change uniform,
    // and click on the Last Adventure link

    SpecialOutfit outfit = EquipmentManager.currentOutfit();

    // If he's not in a uniform, no battle
    if (outfit == null) {
      return false;
    }

    int outfitId = outfit.getOutfitId();
    return switch (outfitId) {
      case 32, 33 ->
      // War Hippy Fatigues
      // Frat Warrior Fatigues
      checkBattle(outfitId);
      default -> false;
    };
  }

  public boolean sendBreakPrismWarning(final String urlString) {
    // place.php?whichplace=nstower&action=ns_11_prism
    if (!urlString.startsWith("place.php")
        || !urlString.contains("whichplace=nstower")
        || !urlString.contains("action=ns_11_prism")) {
      return false;
    }
    // We are about to free King Ralph

    // In Kingdom of Exploathing, you will lose access to Cosmic
    // Ray's Bazaar and can therefore not turn in your rare Meat
    // Isotopes

    if (KoLCharacter.isKingdomOfExploathing()) {
      // If user has already confirmed he wants to break the prism, accept it
      if (this.getFormField(Confirm.RALPH) != null) {
        return false;
      }

      if (InventoryManager.getCount(ItemPool.RARE_MEAT_ISOTOPE) <= 0) {
        return false;
      }

      String warning =
          "You are about to free King Ralph and end your Kingdom of Exploathing run."
              + " Before you do so, you might want to redeem your rare Meat isotopes at Cosmic Ray's Bazaar,"
              + " since you will not be able to do so after you free the king."
              + " If you are ready to break the prism, click on the icon on the left."
              + " If you wish to visit Cosmic Ray's Bazaar, click on icon on the right.";
      this.sendOptionalWarning(
          Confirm.RALPH,
          warning,
          "hand.gif",
          "meatisotope.gif",
          "\"shop.php?whichshop=exploathing\"",
          null,
          null);
      return true;
    }

    if (KoLCharacter.isPlumber()) {
      // If you are already stuffed, you can't eat more.
      if (KoLCharacter.getFullness() < KoLCharacter.getStomachCapacity()
          && !RelayRequest.ignoreFullnessWarning) {
        // If it's already confirmed, then track that for the session
        if (this.getFormField(Confirm.RALPH1) == null) {
          String warning =
              "When you stop being a plumber, you will lose five maximum fullness."
                  + " Since you are not yet full, perhaps you would like to eat more now?"
                  + " (It is not harmful to be over-full.)"
                  + " If you are sure you don't want to eat more at this time, click the icon. ";
          this.sendGeneralWarning("knifefork.gif", warning, Confirm.RALPH1);
          return true;
        }
        RelayRequest.ignoreFullnessWarning = true;
      }

      // *** coins are quest items, but, rather than persisting until ascension, they disappear when
      // *** you free Princess Ralph - as do all items you can spend coins on.
      // *** If this changes, uncomment the following code.

      /*
      // If you don't have any coins, nothing to spend
      if ( InventoryManager.getCount( ItemPool.COIN ) > 0 )
      {
        if ( this.getFormField( Confirm.RALPH2 ) == null )
        {
          StringBuilder warning = new StringBuilder();
          warning.append( "When you stop being a plumber, you will lose access to the Mushroom District." );
          warning.append( " Since you have unspent coins, perhaps you would like to visit some shops?" );
          warning.append( " If you are sure you don't want to spend your coins, click the icon on the left. " );
          warning.append( " If you wish to visit the Mushroom District, click on icon on the right." );
          this.sendOptionalWarning(
            Confirm.RALPH2,
            warning.toString(),
            "mario_coin.gif",
            "mario_mushroom1.gif",
            "place.php?whichplace=mario",
            null,
            null
            );
          return true;
        }
      }
      */

      return false;
    }

    // In You, Robot, you will lose access to the Scrapheap.
    // You might want to spend Energy on Adventures or Stats.

    if (KoLCharacter.inRobocore()) {
      // If user has already confirmed he wants to go there, accept it
      if (this.getFormField(Confirm.RALPH) != null) {
        return false;
      }

      int energy = KoLCharacter.getYouRobotEnergy();
      int chronolithCost = Preferences.getInteger("_chronolithNextCost");
      int statbotCost = Preferences.getInteger("statbotUses") + 10;

      if (energy < chronolithCost && energy < statbotCost) {
        return false;
      }

      StringBuilder buf = new StringBuilder();
      buf.append("You are about to free King Ralph and stop being a Robot.");
      buf.append(" Before you do so, you might want to spend your remaining ");
      buf.append(energy);
      buf.append(" energy in the Scrapheap,");
      buf.append(" since you will not be able to do so after you free the king.");
      if (energy >= chronolithCost) {
        buf.append(" You can gain 10 Adventures at the Chronolith for ");
        buf.append(chronolithCost);
        buf.append(" energy.");
      }
      if (energy >= statbotCost) {
        buf.append(" You can gain 5 points of the stat of your choice at Statbot 5000 for ");
        buf.append(statbotCost);
        buf.append(" energy.");
      }
      buf.append(" If you are ready to break the prism, click on the icon on the left.");
      buf.append(" If you wish to visit the Scrapheap, click on icon on the right.");

      this.sendOptionalWarning(
          Confirm.RALPH,
          buf.toString(),
          "hand.gif",
          "jigawatts.gif",
          "\"place.php?whichplace=scrapheap\"",
          null,
          null);
      return true;
    }

    // In Fall of the Dinosaurs, you will lose access to the Dinostaur and can therefore not spend
    // your dinodollars

    if (KoLCharacter.inDinocore()) {
      // If user has already confirmed he wants to go there, accept it
      if (this.getFormField(Confirm.RALPH) != null) {
        return false;
      }

      if (InventoryManager.getCount(ItemPool.DINODOLLAR) <= 0) {
        return false;
      }

      String warning =
          "You are about to free King Ralph and end your Fall of the Dinosaurs run."
              + " Before you do so, you might want to spend your Dinodollars at the Dino Staur,"
              + " since you will not be able to do so after you free the king."
              + " If you are ready to break the prism, click on the icon on the left."
              + " If you wish to visit the Dino Staur, click on icon on the right.";
      this.sendOptionalWarning(
          Confirm.RALPH,
          warning,
          "hand.gif",
          "dinobuck.gif",
          "\"shop.php?whichshop=dino\"",
          null,
          null);
      return true;
    }

    return false;
  }

  private String getFormField(Confirm confirm) {
    return super.getFormField(confirm.toString());
  }

  private boolean checkCampVisit(final String urlString) {
    CoinmasterData data = IslandManager.findCampMaster(urlString);

    // If he's not attempting to enter a camp, no problem.
    if (data == null) {
      return false;
    }

    SpecialOutfit outfit = EquipmentManager.currentOutfit();

    // If he's not in a uniform, no visit
    if (outfit == null) {
      return false;
    }

    switch (outfit.getOutfitId()) {
      case 32:
        // War Hippy Fatigues
        if (data == DimemasterRequest.HIPPY) {
          return false;
        }
        break;

      case 33:
        // Frat Warrior Fatigues
        if (data == QuartersmasterRequest.FRATBOY) {
          return false;
        }
        break;

      default:
        return false;
    }

    // He is attempting to visit the opposing camp in uniform.
    // This will prompt the final confrontation.
    // Offer a chance to cash in dimes and quarters.

    return this.sendCoinMasterWarning(data);
  }

  private boolean checkBattle(final int outfitId) {
    int fratboysDefeated = IslandManager.fratboysDefeated();
    int hippiesDefeated = IslandManager.hippiesDefeated();

    if (fratboysDefeated == 999 && hippiesDefeated == 999) {
      return this.sendCoinMasterWarning(null);
    }

    if (fratboysDefeated == 999 && outfitId == OutfitPool.WAR_HIPPY_OUTFIT) {
      // In hippy uniform and about to defeat last fratboy.
      int factor = IslandManager.hippiesDefeatedPerBattle();
      if (hippiesDefeated < 999 && (999 - hippiesDefeated) % factor == 0) {
        return this.sendWossnameWarning(QuartersmasterRequest.FRATBOY);
      }
    }

    if (hippiesDefeated == 999 && outfitId == OutfitPool.WAR_FRAT_OUTFIT) {
      // In fratboy uniform and about to defeat last hippy.
      int factor = IslandManager.fratboysDefeatedPerBattle();
      if (fratboysDefeated < 999 && (999 - fratboysDefeated) % factor == 0) {
        return this.sendWossnameWarning(DimemasterRequest.HIPPY);
      }
    }

    return false;
  }

  private boolean sendCoinMasterWarning(final CoinmasterData camp) {
    // If user has already confirmed he wants to go there, accept it
    if (this.getFormField(Confirm.TOKENS) != null) {
      return false;
    }

    String message;

    if (camp == null) {
      message = "You are about to enter the final confrontation with the two bosses.";
    } else {
      String master = (camp == DimemasterRequest.HIPPY ? "hippy" : "fratboy");
      message = "You are about to enter the " + master + " camp and confront the boss.";
    }

    message =
        message
            + " Before you do so, you might want to redeem war loot for dimes and quarters and buy equipment. Click on the image to enter battle, once you are ready.";

    this.sendGeneralWarning("lucre.gif", message, Confirm.TOKENS);

    return true;
  }

  private boolean sendColosseumWarning(final KoLAdventure adventure) {
    if (adventure == null) {
      return false;
    }

    int location = adventure.getAdventureNumber();

    // If he's not going to the Mer-Kin Colosseum, no problem
    if (location != AdventurePool.MERKIN_COLOSSEUM) {
      return false;
    }

    if (this.getFormField(Confirm.COLOSSEUM) != null) {
      return false;
    }

    // See which opponent he is about to face
    int lastRound = Preferences.getInteger("lastColosseumRoundWon");

    // If he is going in to round 1-3 (lastRound 0-2), no special moves
    if (lastRound < 3) {
      return false;
    }

    AdventureResult weapon = null;
    String image = null;
    String opponent = null;

    switch (lastRound % 3) {
      case 0 -> {
        weapon = ItemPool.get(ItemPool.MERKIN_DRAGNET, 1);
        image = "dragnet.gif";
        opponent = lastRound == 12 ? "Georgepaul, the Balldodger" : "a Mer-kin balldodger";
      }
      case 1 -> {
        weapon = ItemPool.get(ItemPool.MERKIN_SWITCHBLADE, 1);
        image = "switchblade.gif";
        opponent = lastRound == 13 ? "Johnringo, the Netdragger" : "a Mer-kin netdragger";
      }
      case 2 -> {
        weapon = ItemPool.get(ItemPool.MERKIN_DODGEBALL, 1);
        image = "dodgeball.gif";
        opponent = lastRound == 14 ? "Ringogeorge, the Bladeswitcher" : "a Mer-kin bladeswitcher";
      }
    }

    // If you are equipped with the correct weapon, nothing to warn about
    if (KoLCharacter.hasEquipped(weapon.getItemId(), Slot.WEAPON)) {
      return false;
    }

    // If you don't own the correct weapon, nothing to warn about
    if (!KoLConstants.inventory.contains(weapon)) {
      return false;
    }

    String warning =
        "KoLmafia has detected that you are about to fight round "
            + (lastRound + 1)
            + " in the Mer-kin Colosseum, where you will face "
            + opponent
            + ". If you are sure you wish to battle him without your "
            + weapon.getName()
            + " equipped, click the icon on the left to adventure. "
            + "If this was an accident, click the icon in the center to equip your "
            + weapon.getName()
            + ". If you want to battle in the Mer-kin Colosseum and not be nagged about your "
            + weapon.getName()
            + ", click the icon on the right to closet it.";
    this.sendOptionalWarning(
        Confirm.COLOSSEUM,
        warning,
        "hand.gif",
        image,
        "\"#\" onClick=\"singleUse('inv_equip.php','which=2&action=equip&whichitem="
            + weapon.getItemId()
            + "&pwd="
            + GenericRequest.passwordHash
            + "&ajax=1');void(0);\"",
        "/images/closet.gif",
        "\"#\" onClick=\"singleUse('fillcloset.php','action=closetpush&whichitem="
            + weapon.getItemId()
            + "&qty=all&pwd="
            + GenericRequest.passwordHash
            + "&ajax=1');void(0);\"");

    return true;
  }

  private boolean sendInfernalSealWarning(final String urlString) {
    // If user has already confirmed he wants to do it, accept it
    if (this.getFormField(Confirm.SEAL) != null) {
      return false;
    }

    // If he's not using an item, nothing to worry about
    if (!urlString.startsWith("inv_use.php")) {
      return false;
    }

    Matcher matcher = GenericRequest.WHICHITEM_PATTERN.matcher(urlString);
    if (!matcher.find()) {
      return false;
    }

    // If it's not a seal figurine, no problem
    if (!ItemDatabase.isSealFigurine(StringUtilities.parseInt(matcher.group(1)))) {
      return false;
    }

    // If he's wielding a club, life is wonderful. ish.
    if (EquipmentManager.wieldingClub()) {
      return false;
    }

    String message;

    message =
        "You are trying to summon an infernal seal, but you are not wielding a club. You are either incredibly puissant or incredibly foolish. If you are sure you want to do this, click on the image and proceed to your doom.";

    this.sendGeneralWarning("iblubbercandle.gif", message, Confirm.SEAL, "checked=1");

    return true;
  }

  private boolean sendGremlinWarning(final KoLAdventure adventure) {
    if (adventure == null) {
      return false;
    }

    int location = adventure.getAdventureNumber();

    // If they aren't going to the War Gremlin zones, no problem
    if (location != AdventurePool.JUNKYARD_BARREL
        && location != AdventurePool.JUNKYARD_REFRIGERATOR
        && location != AdventurePool.JUNKYARD_TIRES
        && location != AdventurePool.JUNKYARD_CAR) {
      return false;
    }

    // If user has already confirmed he wants to do it, accept it
    if (this.getFormField(Confirm.GREMLINS) != null) {
      return false;
    }

    // If they've completed the quest, don't worry
    if (!Preferences.getString("sidequestJunkyardCompleted").equals("none")) {
      return false;
    }

    // If they have the Molybdenum Magnet, no warning needed
    if (InventoryManager.getCount(ItemPool.MOLYBDENUM_MAGNET) > 0) {
      return false;
    }

    String message;

    message =
        "You are about to fight Gremlins, but do not have the Molybdenum Magnet. If you are sure you want to do this, click on the image to proceed.";

    this.sendGeneralWarning("magnet2.gif", message, Confirm.GREMLINS);

    return true;
  }

  private boolean sendHardcorePVPWarning(final String urlString) {
    // If the person is in hardcore and about to free the king,
    // warn them if they are participating in PVP

    if (!urlString.contains("whichplace=nstower")) {
      // We are not even in the tower.
      return false;
    }

    // Don't remind a second time in a session if you decide not to do it.
    if (urlString.contains(Confirm.HARDCOREPVP.toString())) {
      return false;
    }

    // If not talking to King, ignore
    if (!urlString.contains("action=ns_11_prism")) {
      return false;
    }

    // If they're not in hardcore, then ignore
    if (!KoLCharacter.isHardcore()) {
      return false;
    }

    // If they've not asked for the warning, then ignore
    if (!Preferences.getBoolean("hardcorePVPWarning")) {
      return false;
    }

    // If Hippy Stone is intact, then ignore
    if (!KoLCharacter.getHippyStoneBroken()) {
      return false;
    }

    // If no PVP fights left, then ignore
    if (KoLCharacter.getAttacksLeft() == 0) {
      return false;
    }

    String message;

    message =
        "You have fights remaining and are still in Hardcore. If you are sure you don't want to use the fights in hardcore, click on the image to proceed.";

    this.sendGeneralWarning("swords.gif", message, Confirm.HARDCOREPVP);

    return true;
  }

  public boolean sendDesertWeaponWarning() {
    // Only send this warning once per session
    if (RelayRequest.ignoreDesertWeaponWarning) {
      return false;
    }

    // If it's already confirmed, then track that for the session
    if (this.getFormField(Confirm.DESERT_WEAPON) != null) {
      RelayRequest.ignoreDesertWeaponWarning = true;
      return false;
    }

    // If they aren't in the desert, no problem
    int location = StringUtilities.parseInt(this.getFormField("snarfblat"));
    if (AdventurePool.ARID_DESERT != location) {
      return false;
    }

    // All reason to care about extra exploration is gone, no problem
    int explored = Preferences.getInteger("desertExploration");
    if (explored >= 99) {
      return false;
    }

    // If they have survival knife equipped, no problem
    if (KoLCharacter.hasEquipped(ItemPool.SURVIVAL_KNIFE)) {
      return false;
    }

    // You can't equip it in Avatar of Boris or Suprising Fist, so no problem
    if (KoLCharacter.inFistcore() || KoLCharacter.inAxecore()) {
      return false;
    }

    // If you have survival knife, suggest it
    if (InventoryManager.getCount(ItemPool.SURVIVAL_KNIFE) > 0) {
      String warning =
          "You are about to adventure without your survival knife in the desert. "
              + "If you are sure you wish to adventure without it, click the icon on the left to adventure. "
              + "If you want to equip the survival knife first, click the icon on the right. ";
      this.sendOptionalWarning(
          Confirm.DESERT_WEAPON,
          warning,
          "hand.gif",
          "maydayknife.gif",
          "\"#\" onClick=\"singleUse('inv_equip.php','which=2&action=equip&whichitem="
              + ItemPool.SURVIVAL_KNIFE
              + "&pwd="
              + GenericRequest.passwordHash
              + "&ajax=1');void(0);\"",
          null,
          null);
    } else {
      // If you don't have a knife, you can't get one.
      return false;
    }

    return true;
  }

  private boolean sendDesertOffhandWarning() {
    // Only send this warning once per session
    if (RelayRequest.ignoreDesertOffhandWarning) {
      return false;
    }

    // If it's already confirmed, then track that for the session
    if (this.getFormField(Confirm.DESERT_OFFHAND) != null) {
      RelayRequest.ignoreDesertOffhandWarning = true;
      return false;
    }

    // If they aren't in the desert, no problem
    int location = StringUtilities.parseInt(this.getFormField("snarfblat"));
    if (AdventurePool.ARID_DESERT != location) {
      return false;
    }

    // All reason to care about extra exploration is gone, no problem
    int explored = Preferences.getInteger("desertExploration");
    if (explored >= 99) {
      return false;
    }

    // If they have compass or ornate dowsing rod equipped, no problem
    if (KoLCharacter.hasEquipped(ItemPool.UV_RESISTANT_COMPASS)
        || KoLCharacter.hasEquipped(ItemPool.DOWSING_ROD)) {
      return false;
    }
    // Since The Shore is unavailable in Kingdom of Exploathing and you can't get one, so no problem
    if (KoLCharacter.isKingdomOfExploathing()) {
      return false;
    }

    // You can't equip them in Avatar of Boris or Suprising Fist, so no problem
    if (KoLCharacter.inFistcore() || KoLCharacter.inAxecore()) {
      return false;
    }

    // If you have dowsing rod, suggest it
    if (InventoryManager.getCount(ItemPool.DOWSING_ROD) > 0) {

      String warning =
          "You are about to adventure without your ornate dowsing rod in the desert. "
              + "If you are sure you wish to adventure without it, click the icon on the left to adventure. "
              + "If you want to equip the ornate dowsing rod first, click the icon on the right. ";
      this.sendOptionalWarning(
          Confirm.DESERT_OFFHAND,
          warning,
          "hand.gif",
          "dowsingrod.gif",
          "\"#\" onClick=\"singleUse('inv_equip.php','which=2&action=equip&whichitem="
              + ItemPool.DOWSING_ROD
              + "&pwd="
              + GenericRequest.passwordHash
              + "&ajax=1');void(0);\"",
          null,
          null);
    }
    // You can't use UV compass in G-Lover
    else if (KoLCharacter.inGLover()) {
      return false;
    }
    // If you have UV Compass, suggest it
    else if (InventoryManager.getCount(ItemPool.UV_RESISTANT_COMPASS) > 0) {

      String warning =
          "You are about to adventure without your UV-resistant compass in the desert. "
              + "If you are sure you wish to adventure without it, click the icon on the left to adventure. "
              + "If you want to equip the UV-resistant compass first, click the icon on the right. ";
      this.sendOptionalWarning(
          Confirm.DESERT_OFFHAND,
          warning,
          "hand.gif",
          "uvcompass.gif",
          "\"#\" onClick=\"singleUse('inv_equip.php','which=2&action=equip&whichitem="
              + ItemPool.UV_RESISTANT_COMPASS
              + "&pwd="
              + GenericRequest.passwordHash
              + "&ajax=1');void(0);\"",
          null,
          null);
    }
    // Otherwise just ask if you want to adventure
    else {
      String message =
          "You are about to adventure without a UV-resistant compass in the desert. If you are sure you want to do this, click on the image to proceed.";

      this.sendGeneralWarning("uvcompass.gif", message, Confirm.DESERT_OFFHAND);
    }

    return true;
  }

  public boolean sendUnhydratedDesertWarning() {
    // Only send this warning once per session
    if (RelayRequest.ignoreDesertWarning) {
      return false;
    }

    // If it's already confirmed, then track that for the session
    if (this.getFormField(Confirm.DESERT_UNHYDRATED) != null) {
      RelayRequest.ignoreDesertWarning = true;
      return false;
    }

    // If they aren't in the desert, no problem
    int location = StringUtilities.parseInt(this.getFormField("snarfblat"));
    if (AdventurePool.ARID_DESERT != location) {
      return false;
    }

    // If The Oasis isn't open, exploring the first time will hydrate and open it
    if (!Preferences.getBoolean("oasisAvailable")) {
      return false;
    }

    // If the desert is fully explored, no reason to care about hydration
    int explored = Preferences.getInteger("desertExploration");
    if (explored == 100) {
      return false;
    }

    // If they are already Ultrahydrated, no problem
    if (KoLConstants.activeEffects.contains(EffectPool.get(EffectPool.HYDRATED))) {
      return false;
    }

    String warning =
        "You are about to adventure unhydrated in the desert. "
            + "If you are sure you wish to adventure unhydrated, click the icon on the left to adventure. "
            + "If you want to visit the Oasis to get ultrahydrated, click the icon on the right to adventure. ";
    this.sendOptionalWarning(
        Confirm.DESERT_UNHYDRATED,
        warning,
        "poison.gif",
        "raindrop.gif",
        "\"adventure.php?snarfblat=122\"",
        null,
        null);

    return true;
  }

  private void sendMacheteWarning(int itemId) {
    String name = ItemDatabase.getItemDataName(itemId);
    String image = ItemDatabase.getImage(itemId);

    StringBuilder buf = new StringBuilder();

    buf.append("You are about to adventure without your ");
    buf.append(name);
    buf.append(" to fight dense lianas.");

    // Message when there is no suggested remedy
    String ok1 = " If you are sure you wish to adventure without it, click the icon to adventure.";
    // Message when there is a suggested remedy
    String ok2 =
        " If you are sure you wish to adventure without it, click the icon on the left to adventure.";

    if (EquipmentManager.canEquip(itemId)) {
      buf.append(ok2);
      buf.append(" If you want to equip the ");
      buf.append(name);
      buf.append(" first, click the icon on the right.");

      this.sendOptionalWarning(
          Confirm.MACHETE,
          buf.toString(),
          "hand.gif",
          image,
          "\"#\" onClick=\"singleUse('inv_equip.php','which=2&action=equip&whichitem="
              + itemId
              + "&pwd="
              + GenericRequest.passwordHash
              + "&ajax=1');void(0);\"",
          null,
          null);
      return;
    }

    // If they can't equip it:
    //   perhaps they don't meet the stat requirements
    //   perhaps they can't wield a weapon (as a Robot)

    boolean lowStats = !EquipmentManager.meetsStatRequirements(itemId);
    if (lowStats) {
      EquipmentRequirement req =
          new EquipmentRequirement(EquipmentDatabase.getEquipRequirement(itemId));
      buf.append(" It requires base Muscle of ");
      buf.append(req.getAmount());
      buf.append(", but yours is only ");
      buf.append(KoLCharacter.getBaseMuscle());
      buf.append(".");
    }

    if (KoLCharacter.inRobocore()) {
      String resource;
      if (lowStats) {
        buf.append(" Perhaps it is time to visit Statbot 5000.");
        resource = "jigawatts.gif";
      } else {
        buf.append(" You need to attach Vice Grips in order to wield a weapon.");
        resource = "scrap.gif";
      }

      buf.append(ok2);
      buf.append(" If you want to visit the Scrapheap, click the icon on the right.");
      this.sendOptionalWarning(
          Confirm.MACHETE,
          buf.toString(),
          "hand.gif",
          resource,
          "\"place.php?whichplace=scrapheap\"",
          null,
          null);

      return;
    }

    buf.append(ok1);
    this.sendGeneralWarning("hand.gif", buf.toString(), Confirm.MACHETE);
  }

  public boolean sendMacheteWarning() {
    // Only send this warning once per session
    if (RelayRequest.ignoreMacheteWarning) {
      return false;
    }

    // If it's already confirmed, then track that for the session
    if (this.getFormField(Confirm.MACHETE) != null) {
      RelayRequest.ignoreMacheteWarning = true;
      return false;
    }

    // If quest is finished, all lianas are gone
    if (QuestDatabase.isQuestFinished(Quest.WORSHIP)) {
      return false;
    }

    // New property added to allow scripts to track whether the lianas guarding
    // the Massive Ziggurat are clear without having to look at turns spent at
    // the location.
    if (AdventureSpentDatabase.getTurns("A Massive Ziggurat") >= 3) {
      Preferences.setInteger("zigguratLianas", 1);
    }

    // If they aren't in a liana location, or the lianas are defeated, no problem
    int location = StringUtilities.parseInt(this.getFormField("snarfblat"));
    if (!((AdventurePool.NE_SHRINE == location
            && Preferences.getInteger("hiddenOfficeProgress") == 0)
        || (AdventurePool.SE_SHRINE == location
            && Preferences.getInteger("hiddenBowlingAlleyProgress") == 0)
        || (AdventurePool.NW_SHRINE == location
            && Preferences.getInteger("hiddenApartmentProgress") == 0)
        || (AdventurePool.SW_SHRINE == location
            && Preferences.getInteger("hiddenHospitalProgress") == 0)
        || (AdventurePool.ZIGGURAT == location && Preferences.getInteger("zigguratLianas") == 0))) {
      return false;
    }

    // If they have a machete equipped, no problem
    if (KoLCharacter.hasEquipped(ItemPool.PAPIER_MACHETE, Slot.WEAPON)
        || KoLCharacter.hasEquipped(ItemPool.MUCULENT_MACHETE, Slot.WEAPON)
        || KoLCharacter.hasEquipped(ItemPool.MACHETITO, Slot.WEAPON)
        || KoLCharacter.hasEquipped(ItemPool.ANTIQUE_MACHETE, Slot.WEAPON)) {
      return false;
    }

    // You can't equip them in Avatar of Boris, Suprising Fist or G-Lover, so no problem
    // They don't help in Avant Guard, so also no problem
    if (KoLCharacter.inFistcore()
        || KoLCharacter.inAxecore()
        || KoLCharacter.inGLover()
        || KoLCharacter.inAvantGuard()) {
      return false;
    }

    // These checks are ordered from lowest Base Muscle to highest.  If you
    // have more than one kind of machete, if you can't equip one, you can't
    // equip the heftier ones either.

    // If you have muculent machete, suggest it
    if (InventoryManager.getCount(ItemPool.MUCULENT_MACHETE) > 0) {
      sendMacheteWarning(ItemPool.MUCULENT_MACHETE);
      return true;
    }

    // If you have papier machete, suggest it
    if (InventoryManager.getCount(ItemPool.PAPIER_MACHETE) > 0) {
      sendMacheteWarning(ItemPool.PAPIER_MACHETE);
      return true;
    }

    // If you have machetito, suggest it
    if (InventoryManager.getCount(ItemPool.MACHETITO) > 0) {
      sendMacheteWarning(ItemPool.MACHETITO);
      return true;
    }

    // If you have antique machete, suggest it
    if (InventoryManager.getCount(ItemPool.ANTIQUE_MACHETE) > 0) {
      sendMacheteWarning(ItemPool.ANTIQUE_MACHETE);
      return true;
    }

    // Otherwise just ask if you want to adventure
    String message =
        "You are about to adventure without a machete to fight dense lianas. If you are sure you want to do this, click on the image to proceed.";
    this.sendGeneralWarning("machetwo.gif", message, Confirm.MACHETE);

    return true;
  }

  public boolean sendMohawkWigWarning() {
    // Only send this warning once per session
    if (RelayRequest.ignoreMohawkWigWarning) {
      return false;
    }

    // If it's already confirmed, then track that for the session
    if (this.getFormField(Confirm.MOHAWK_WIG) != null) {
      RelayRequest.ignoreMohawkWigWarning = true;
      return false;
    }

    // If they aren't in the Castle Top, no problem
    int location = StringUtilities.parseInt(this.getFormField("snarfblat"));
    if (AdventurePool.CASTLE_TOP != location) {
      return false;
    }

    // If they have already turned the chore wheel, no problem
    if (QuestDatabase.isQuestLaterThan(Quest.GARBAGE, "step9")) {
      return false;
    }

    // If they are already wearing the Wig, no problem
    if (KoLCharacter.hasEquipped(ItemPool.MOHAWK_WIG, Slot.HAT)) {
      return false;
    }

    // If they don't have the Wig, no problem
    if (!InventoryManager.hasItem(ItemPool.MOHAWK_WIG)) {
      return false;
    }

    StringBuilder buf = new StringBuilder();
    buf.append("You are about to adventure without your Mohawk Wig in the Castle.");

    // Message when there is no suggested remedy
    String ok1 = " If you are sure you wish to adventure without it, click the icon to adventure.";
    // Message when there is a suggested remedy
    String ok2 =
        " If you are sure you wish to adventure without it, click the icon on the left to adventure.";

    // If they can equip it, give them the option to do so.
    if (EquipmentManager.canEquip(ItemPool.MOHAWK_WIG)) {
      buf.append(ok2);
      buf.append(" If you want to put the hat on first, click the icon on the right.");

      this.sendOptionalWarning(
          Confirm.MOHAWK_WIG,
          buf.toString(),
          "hand.gif",
          "mohawk.gif",
          "\"#\" onClick=\"singleUse('inv_equip.php','which=2&action=equip&whichitem="
              + ItemPool.MOHAWK_WIG
              + "&pwd="
              + GenericRequest.passwordHash
              + "&ajax=1');void(0);\"",
          null,
          null);

      return true;
    }

    // If they can't equip it:
    //   perhaps they don't meet the stat requirements
    //   perhaps they can't wear a hat (as a Robot)

    boolean lowStats = !EquipmentManager.meetsStatRequirements(ItemPool.MOHAWK_WIG);
    if (lowStats) {
      buf.append(" It requires base Moxie of 55, but yours is only ");
      buf.append(KoLCharacter.getBaseMoxie());
      buf.append(".");
    }

    if (KoLCharacter.inRobocore()) {
      String image;
      if (lowStats) {
        buf.append(" Perhaps it is time to visit Statbot 5000.");
        image = "jigawatts.gif";
      } else {
        buf.append(" You need to attach a Mannequin Head in order to wear a hat.");
        image = "scrap.gif";
      }

      buf.append(ok2);
      buf.append(" If you want to visit the Scrapheap, click the icon on the right.");

      this.sendOptionalWarning(
          Confirm.MOHAWK_WIG,
          buf.toString(),
          "hand.gif",
          image,
          "\"place.php?whichplace=scrapheap\"",
          null,
          null);

      return true;
    }

    buf.append(ok1);
    this.sendGeneralWarning("hand.gif", buf.toString(), Confirm.MOHAWK_WIG);

    return true;
  }

  public boolean sendSpringShoesWarning() {
    // place.php?whichplace=plains&action=garbage_grounds
    String urlString = this.getURLString();
    if (!urlString.startsWith("place.php")
        || !urlString.contains("whichplace=plains")
        || !urlString.contains("garbage_grounds")) {
      return false;
    }

    // If you're a Zootimist, you can't get stats
    if (KoLCharacter.inZootomist()) {
      return false;
    }

    // If it's already confirmed, then proceed
    if (this.getFormField(Confirm.SPRING_SHOES) != null) {
      return false;
    }

    // If they are already wearing spring shoes, no problem
    if (KoLCharacter.hasEquipped(ItemPool.SPRING_SHOES)) {
      return false;
    }

    // If they don't have spring shoes, no problem
    if (!InventoryManager.hasItem(ItemPool.SPRING_SHOES)) {
      return false;
    }

    StringBuilder buf = new StringBuilder();
    buf.append("You are about to plant an enchanted bean without wearing your spring shoes.");
    buf.append(
        " If you are sure you wish to plant without it, click the icon on the left to do so.");
    buf.append(" If you want to put the shoes on first, click the icon on the right.");

    this.sendOptionalWarning(
        Confirm.SPRING_SHOES,
        buf.toString(),
        "hand.gif",
        "springshoes.gif",
        "\"#\" onClick=\"singleUse('inv_equip.php','which=2&action=equip&slot=3&whichitem="
            + ItemPool.SPRING_SHOES
            + "&pwd="
            + GenericRequest.passwordHash
            + "&ajax=1');void(0);\"",
        null,
        null);

    return true;
  }

  private boolean sendBoringDoorsWarning() {
    // Only send this warning once per session
    if (RelayRequest.ignoreBoringDoorsWarning) {
      return false;
    }

    // If it's already confirmed, then track that for the session
    if (this.getFormField(Confirm.BORING_DOORS) != null) {
      RelayRequest.ignoreBoringDoorsWarning = true;
      return false;
    }

    // If they aren't in the Daily Dungeon, no problem
    int location = StringUtilities.parseInt(this.getFormField("snarfblat"));
    if (AdventurePool.THE_DAILY_DUNGEON != location) {
      return false;
    }

    // If they are not about to enter chambers 5 or 10, no problem
    int nextChamber = Preferences.getInteger("_lastDailyDungeonRoom") + 1;
    if (nextChamber != 5 && nextChamber != 10) {
      return false;
    }

    // If they are already wearing the Ring, no problem
    if (KoLCharacter.hasEquipped(ItemPool.get(ItemPool.RING_OF_DETECT_BORING_DOORS, 1))) {
      return false;
    }

    // If they don't have the Ring, no problem
    if (!InventoryManager.hasItem(ItemPool.RING_OF_DETECT_BORING_DOORS)) {
      return false;
    }

    String warning =
        "You are about to adventure without your Ring of Detect Boring Doors in the "
            + nextChamber
            + "th Chamber of the Daily Dungeon. "
            + "If you are sure you wish to adventure without it, click the icon on the left to adventure. "
            + "If you want to put the ring on first, click the icon on the right. ";
    this.sendOptionalWarning(
        Confirm.BORING_DOORS,
        warning,
        "hand.gif",
        "weddingring.gif",
        "\"#\" onClick=\"singleUse('inv_equip.php','which=2&action=equip&slot=3&whichitem="
            + ItemPool.RING_OF_DETECT_BORING_DOORS
            + "&pwd="
            + GenericRequest.passwordHash
            + "&ajax=1');void(0);\"",
        null,
        null);

    return true;
  }

  private boolean sendPoolSkillWarning() {
    // Only send this warning once per session
    if (RelayRequest.ignorePoolSkillWarning) {
      return false;
    }

    // If it's already confirmed, then track that for the session
    if (this.getFormField(Confirm.POOL_SKILL) != null) {
      RelayRequest.ignorePoolSkillWarning = true;
      return false;
    }

    // If they aren't in the Billiards Room, no problem
    int location = StringUtilities.parseInt(this.getFormField("snarfblat"));
    if (AdventurePool.HAUNTED_BILLIARDS_ROOM != location) {
      return false;
    }

    // If Lights Out due, no problem
    if (LightsOutManager.lightsOutNow()) {
      return false;
    }

    // If they have already have the library key, no problem
    if (InventoryManager.getCount(ItemPool.LIBRARY_KEY) > 0) {
      return false;
    }

    // Calculate current pool skill
    int poolSkill = KoLCharacter.estimatedPoolSkill();

    // If pool skill 18 or greater, no problem (based on current spading, no failures at 18)
    if (poolSkill >= 18) {
      return false;
    }

    // Just consider the common items, pool cue and hand chalk
    String image2 = null;
    String action2 = null;
    String image3 = null;
    String action3 = null;

    // If they don't have Staff of Ed (as Ed), Staff of Fats or Pool Cue equipped, but do have them,
    // present the best as an option
    if (!KoLCharacter.hasEquipped(ItemPool.STAFF_OF_FATS, Slot.WEAPON)
        && InventoryManager.hasItem(ItemPool.STAFF_OF_FATS)) {
      image2 = "poolcuef.gif";
      action2 =
          "\"#\" onClick=\"singleUse('inv_equip.php','which=2&action=equip&whichitem="
              + ItemPool.STAFF_OF_FATS
              + "&pwd="
              + GenericRequest.passwordHash
              + "&ajax=1');void(0);\"";
    } else if (!KoLCharacter.hasEquipped(ItemPool.ED_STAFF, Slot.WEAPON)
        && InventoryManager.hasItem(ItemPool.ED_STAFF)) {
      image2 = "staffofed.gif";
      action2 =
          "\"#\" onClick=\"singleUse('inv_equip.php','which=2&action=equip&whichitem="
              + ItemPool.ED_STAFF
              + "&pwd="
              + GenericRequest.passwordHash
              + "&ajax=1');void(0);\"";
    } else if (!KoLCharacter.hasEquipped(ItemPool.ED_FATS_STAFF, Slot.WEAPON)
        && InventoryManager.hasItem(ItemPool.ED_FATS_STAFF)) {
      image2 = "poolcuef.gif";
      action2 =
          "\"#\" onClick=\"singleUse('inv_equip.php','which=2&action=equip&whichitem="
              + ItemPool.ED_FATS_STAFF
              + "&pwd="
              + GenericRequest.passwordHash
              + "&ajax=1');void(0);\"";
    } else if (!KoLCharacter.hasEquipped(ItemPool.POOL_CUE, Slot.WEAPON)
        && InventoryManager.hasItem(ItemPool.POOL_CUE)) {
      image2 = "poolcue.gif";
      action2 =
          "\"#\" onClick=\"singleUse('inv_equip.php','which=2&action=equip&whichitem="
              + ItemPool.POOL_CUE
              + "&pwd="
              + GenericRequest.passwordHash
              + "&ajax=1');void(0);\"";
    }

    // If they don't have the Chalky Hand effect, but do have hand chalk, it's an option
    if (!KoLConstants.activeEffects.contains(EffectPool.get(EffectPool.CHALKY_HAND))
        && InventoryManager.hasItem(ItemPool.HAND_CHALK)) {
      image3 = "disease.gif";
      action3 =
          "\"#\" onClick=\"singleUse('inv_use.php','which=3&whichitem="
              + ItemPool.HAND_CHALK
              + "&pwd="
              + GenericRequest.passwordHash
              + "&ajax=1');void(0);\"";
    }

    if (image2 == null && image3 != null) {
      image2 = image3;
      image3 = null;
      action2 = action3;
      action3 = null;
    }

    StringBuilder warning = new StringBuilder();

    if (poolSkill >= 14) {
      warning
          .append("You can't guarantee beating the hustler. You have ")
          .append(poolSkill)
          .append(" pool skill and need 18 to guarantee it. ");
    } else {
      warning
          .append("You cannot beat the hustler. You have ")
          .append(poolSkill)
          .append(" pool skill and need 14 to have a chance, and 18 to guarantee it. ");
    }

    if (!KoLCharacter.canDrink()) {
      if (image2 == null) {
        warning.append("<br>If you are sure you wish to adventure, click the icon. ");
      } else {
        warning.append("<br>If you are sure you wish to adventure, click the icon on the left. ");
      }
    } else {
      if (KoLCharacter.getInebriety() < 10) {
        warning.append(
            "<br>Drinking more may help, giving an extra one pool skill per drunk up to 10.");
      }

      if (image2 == null) {
        warning.append(
            "<br>If you are sure you wish to adventure at this drunkenness, click the icon to adventure. ");
      } else {
        warning.append(
            "<br>If you are sure you wish to adventure at this drunkenness, click the icon on the left to adventure. ");
      }
    }

    if (image3 == null) {
      if ("poolcue.gif".equals(image2)) {
        warning.append(
            "<br>If you want to wield the cue first, for an extra three skill, click the icon on the right. ");
      } else if ("poolcuef.gif".equals(image2)) {
        warning.append(
            "<br>If you want to wield the Staff of Fats first, for an extra five skill, click the icon on the right. ");
        image2 = "poolcue.gif";
      } else if ("staffofed.gif".equals(image2)) {
        warning.append(
            "<br>If you want to wield the Staff of Ed first, for an extra five skill, click the icon on the right. ");
      } else if ("disease.gif".equals(image2)) {
        warning.append(
            "<br>If you want to use hand chalk first, for an extra three skill, click the icon on the right. ");
      }
    } else if ("disease.gif".equals(image3)) {
      warning.append(
          "<br>If you want to wield the cue first, for an extra three skill, click the icon in the middle. ");
      warning.append(
          "<br>If you want to use hand chalk first, for an extra three skill, click the icon in the right. ");
    }

    this.sendOptionalWarning(
        Confirm.POOL_SKILL, warning.toString(), "glove.gif", image2, action2, image3, action3);

    return true;
  }

  boolean sendCellarWarning() {
    // If it's already confirmed, then track that for the session
    if (this.getFormField(Confirm.CELLAR) != null) {
      return false;
    }

    // If they aren't in the Laundry Room or Wine Cellar, no problem
    int location = StringUtilities.parseInt(this.getFormField("snarfblat"));
    if (AdventurePool.HAUNTED_WINE_CELLAR != location
        && AdventurePool.HAUNTED_LAUNDRY_ROOM != location) {
      return false;
    }

    // If Lights Out due, no problem
    if (LightsOutManager.lightsOutNow()) {
      return false;
    }

    // If Vote Monster due, no problem
    if (VoteMonsterManager.voteMonsterNow()) {
      return false;
    }

    // If they have already got access to Summoning Chamber, no problem
    if (QuestDatabase.isQuestLaterThan(Quest.MANOR, "step2")) {
      return false;
    }

    // If they have already read the recipe with glasses, no problem
    if (Preferences.getString("spookyravenRecipeUsed").equals("with_glasses")) {
      return false;
    }

    // If they don't have Lord Spookyraven's spectacles, there is nothing we can do.
    if (!InventoryManager.hasItem(ItemPool.SPOOKYRAVEN_SPECTACLES)) {
      return false;
    }

    // If they don't have the recipe, offer to get it.
    // If autoQuest is true, ResultProcessor will read it with glasses equipped.
    // If autoQuest is false, we'll offer to equip the glasses and then read it
    if (!InventoryManager.hasItem(ItemPool.MORTAR_DISSOLVING_RECIPE)) {
      String read = Preferences.getBoolean("autoQuest") ? " and read" : "";
      String warning =
          "You are about to adventure without having found the recipe for the mortar-dissolving solution."
              + " If you are sure you want to do this, click on the icon on the left to proceed."
              + " If you want to obtain"
              + read
              + " the recipe, click the icon on the right.";

      this.sendOptionalWarning(
          Confirm.CELLAR,
          warning,
          "hand.gif",
          "burgerrecipe.gif",
          "/place.php?whichplace=manor4&action=manor4_chamberwall",
          null,
          null);

      return true;
    }

    // If already confirmed, then allow it this time.
    if (this.getFormField(Confirm.CELLAR2) != null) {
      return false;
    }

    // They have the recipe and have not read it. If they have glasses equipped, offer to read it.
    if (KoLCharacter.hasEquipped(ItemPool.SPOOKYRAVEN_SPECTACLES)) {
      String warning =
          "You are about to adventure without reading the recipe for the mortar-dissolving solution with glasses equipped."
              + " If you are sure you want to do this, click on the icon on the left to proceed."
              + " Since you have the glasses equipped, if you want to read the recipe, click the icon on the right.";

      String action =
          "\"#\" onClick=\"singleUse('inv_use.php','which=3&whichitem="
              + ItemPool.MORTAR_DISSOLVING_RECIPE
              + "&pwd="
              + GenericRequest.passwordHash
              + "&ajax=1');void(0);\"";

      this.sendOptionalWarning(
          Confirm.CELLAR2, warning, "hand.gif", "burgerrecipe.gif", action, null, null);

      return true;
    }

    // If already confirmed, then allow it this time.
    if (this.getFormField(Confirm.CELLAR3) != null) {
      return false;
    }

    String warning =
        "You are about to adventure without reading the recipe for the mortar-dissolving solution with glasses equipped."
            + " If you are sure you want to do this, click on the icon on the left to proceed."
            + " If you want to equip the glasses before reading the recipe, click the icon on the right.";

    String action =
        "\"#\" onClick=\"singleUse('inv_equip.php','which=2&action=equip&slot=3&whichitem="
            + ItemPool.SPOOKYRAVEN_SPECTACLES
            + "&pwd="
            + GenericRequest.passwordHash
            + "&ajax=1');void(0);\"";

    this.sendOptionalWarning(
        Confirm.CELLAR3, warning, "hand.gif", "burgerrecipe.gif", action, null, null);

    return true;
  }

  private boolean sendZeppelinWarning() {
    // If it's already confirmed, then track that for the session
    if (this.getFormField(Confirm.ZEPPELIN) != null) {
      return false;
    }

    // If they aren't in the Zeppelin, no problem
    int location = StringUtilities.parseInt(this.getFormField("snarfblat"));
    if (AdventurePool.RED_ZEPPELIN != location) {
      return false;
    }

    // If they have completed the Zeppelin quest, no problem
    if (QuestDatabase.isQuestFinished(Quest.RON)) {
      return false;
    }

    // If they have already got the ticket, no problem
    if (InventoryManager.hasItem(ItemPool.ZEPPELIN_TICKET)) {
      return false;
    }

    // If the ticket isn't available, don't bother prompting
    if (KoLCharacter.inNuclearAutumn() || KoLCharacter.inFistcore()) {
      return false;
    }

    String warning =
        "You are about to adventure in the Red Zeppelin, but do not have a Zeppelin Ticket. "
            + "If you are sure you wish to adventure without it, click the icon on the left to adventure. "
            + "If you want to visit the Black Market, click the icon on the right. ";
    this.sendOptionalWarning(
        Confirm.ZEPPELIN,
        warning,
        "hand.gif",
        "zepticket.gif",
        "\"shop.php?whichshop=blackmarket\"",
        null,
        null);

    return true;
  }

  public boolean sendBoilerWarning() {
    // If already confirmed, then allow it this time.
    if (this.getFormField(Confirm.BOILER) != null) {
      return false;
    }

    // If they aren't in the Boiler Room, no problem
    int location = StringUtilities.parseInt(this.getFormField("snarfblat"));
    if (AdventurePool.HAUNTED_BOILER_ROOM != location) {
      return false;
    }

    // If Lights Out due, no problem
    if (LightsOutManager.lightsOutNow()) {
      return false;
    }

    // If Vote Monster due, no problem
    if (VoteMonsterManager.voteMonsterNow()) {
      return false;
    }

    // If they have already got access to Summoning Chamber, no problem
    if (QuestDatabase.isQuestLaterThan(Quest.MANOR, "step2")) {
      return false;
    }

    // If they have already got the wine bomb, no problem
    if (InventoryManager.hasItem(ItemPool.WINE_BOMB)) {
      return false;
    }

    // If they are already wielding the fulminate, no problem
    if (KoLCharacter.hasEquipped(ItemPool.UNSTABLE_FULMINATE, Slot.OFFHAND)) {
      return false;
    }

    if (InventoryManager.hasItem(ItemPool.UNSTABLE_FULMINATE)) {
      // They have it, but it is not equipped
      StringBuilder buf = new StringBuilder();
      buf.append(
          "You are about to adventure in the Haunted Boiler Room, but do not have unstable fulminate equipped.");
      buf.append(" If you are sure you want to do this, click the icon on the left to proceed.");
      buf.append(" If you want to equip the fulminate first, click the icon on the right.");

      this.sendOptionalWarning(
          Confirm.BOILER,
          buf.toString(),
          "hand.gif",
          "wine2.gif",
          "\"#\" onClick=\"singleUse('inv_equip.php','which=2&action=equip&whichitem="
              + ItemPool.UNSTABLE_FULMINATE
              + "&pwd="
              + GenericRequest.passwordHash
              + "&ajax=1');void(0);\"",
          null,
          null);

      return true;
    }

    // They don't have it, but perhaps they can make it.

    // If already confirmed, then allow it this time.
    if (this.getFormField(Confirm.BOILER2) != null) {
      return false;
    }

    Concoction recipe = ConcoctionPool.get(ItemPool.UNSTABLE_FULMINATE);
    if (!recipe.hasIngredients()) {
      // If they don't have the ingredients for unstable fulminate, it's a
      // problem, but there's nothing we can do about it.
      return false;
    }

    // They have the ingredients, but do they have a range?
    if (KoLCharacter.hasRange()) {
      // They have a range installed and can to make the fulminate
      // Since it's a fancy recipe, it might take a turn to do so.

      StringBuilder buf = new StringBuilder();
      buf.append(
          "You are about to adventure in the Haunted Boiler Room, but do not have unstable fulminate equipped.");
      buf.append(" You don't have that item, but you have the ingredients and could make it.");
      buf.append(" If you don't want to bother doing this, click the icon on the left to proceed.");
      buf.append(" If you want to make the fulminate now, click the icon on the right.");

      this.sendOptionalWarning(
          Confirm.BOILER2,
          buf.toString(),
          "hand.gif",
          "wine2.gif",
          "\"#\" onClick=\"singleUse('craft.php','action=craft&mode=cook&a="
              + ItemPool.BOTTLE_OF_CHATEAU_DE_VINEGAR
              + "&b="
              + ItemPool.BLASTING_SODA
              + "&qty=1&pwd="
              + GenericRequest.passwordHash
              + "&ajax=1');void(0);\"",
          null,
          null);

      return true;
    }

    // They don't have a range installed, but perhaps they own one.

    // If already confirmed, then allow it this time.
    if (this.getFormField(Confirm.BOILER3) != null) {
      return false;
    }

    if (InventoryManager.getCount(ItemPool.RANGE) > 0) {
      // They have a range in inventory but forgot to install it.
      // We can fix that.

      StringBuilder buf = new StringBuilder();
      buf.append(
          "You are about to adventure in the Haunted Boiler Room, but do not have unstable fulminate equipped.");
      buf.append(" You don't have that item, but you have the ingredients and could make it.");
      buf.append(" It requires a Dramatic range, and you own one, but it is not installed.");
      buf.append(" If you don't want to bother doing this, click the icon on the left to proceed.");
      buf.append(
          " If you want to install the Dramatic range in your kitchen, click the icon on the right.");

      this.sendOptionalWarning(
          Confirm.BOILER3,
          buf.toString(),
          "hand.gif",
          "oven.gif",
          "\"#\" onClick=\"singleUse('inv_use.php','which=3&whichitem="
              + ItemPool.RANGE
              + "&pwd="
              + GenericRequest.passwordHash
              + "&ajax=1');void(0);\"",
          null,
          null);

      return true;
    }

    return false;
  }

  public boolean sendTransformWarning() {
    // If already confirmed, then allow it this time.
    if (this.getFormField(Confirm.TRANSFORM) != null) {
      return false;
    }

    // If they are not a Mild-Mannered Professor, no problem
    if (!KoLCharacter.isMildManneredProfessor()) {
      return false;
    }

    // Unless they are about to turn into a Savage Beast, no problem
    if (Preferences.getInteger("wereProfessorTransformTurns") != 1) {
      return false;
    }

    // If they aren't about to adventure, no problem
    int location = StringUtilities.parseInt(this.getFormField("snarfblat"));
    if (location == 0) {
      return false;
    }

    int rp = Preferences.getInteger("wereProfessorResearchPoints");
    Set<Research> available = ResearchBenchRequest.loadResearch("beastSkillsAvailable");
    Set<Research> researchable = new TreeSet<>();
    for (var research : available) {
      if (research.cost() <= rp) {
        researchable.add(research);
      }
    }

    // If can't afford to research anything, no problem
    if (researchable.size() == 0) {
      return false;
    }

    StringBuilder buf = new StringBuilder();
    buf.append(
        "You are due to transform into a Savage Beast, but have enough Research Points to learn the following beast skills: ");
    String comma = "";
    for (var research : researchable) {
      buf.append(comma);
      comma = ", ";
      buf.append(research.field());
    }
    buf.append(". If you are sure you want to do this, click the icon on the left to proceed.");
    buf.append(" If you want to visit your Research Bench, click the icon on the right.");

    this.sendOptionalWarning(
        Confirm.TRANSFORM,
        buf.toString(),
        "hand.gif",
        "testtube.gif",
        "\"place.php?whichplace=wereprof_cottage&action=wereprof_researchbench\"",
        null,
        null);

    return true;
  }

  private boolean sendDiaryWarning() {
    // If it's already confirmed, then track that for the session
    if (this.getFormField(Confirm.DIARY) != null) {
      return false;
    }

    int location = StringUtilities.parseInt(this.getFormField("snarfblat"));
    // If they aren't in the Poop Deck or Hidden Temple, no problem
    if (AdventurePool.POOP_DECK != location && AdventurePool.HIDDEN_TEMPLE != location) {
      return false;
    }

    // If they aren't in the Ballroom, or are in Ballroom with Lights Out due, no problem
    if (AdventurePool.HAUNTED_BALLROOM != location
        || LightsOutManager.lightsOutNow()
        || VoteMonsterManager.voteMonsterNow()) {
      return false;
    }

    // If they are are lower than level 11, no problem
    if (KoLCharacter.getLevel() < 11) {
      return false;
    }

    // If they have already got the diary, which we read automatically, no problem
    if (InventoryManager.hasItem(ItemPool.MACGUFFIN_DIARY)
        || InventoryManager.hasItem(ItemPool.ED_DIARY)) {
      return false;
    }

    // Sanity check
    if (QuestDatabase.isQuestLaterThan(Quest.BLACK, "step2")) {
      return false;
    }

    String message;

    message =
        "You are about to adventure but have not obtained your father's MacGuffin diary. If you are sure you want to do this, click on the image to proceed.";

    this.sendGeneralWarning("book2.gif", message, Confirm.DIARY);

    return true;
  }

  private boolean sendWossnameWarning(final CoinmasterData camp) {
    if (this.getFormField(Confirm.WOSSNAME) != null) {
      return false;
    }

    String side1 = (camp == DimemasterRequest.HIPPY ? "hippy" : "fratboy");
    String side2 = (camp == DimemasterRequest.HIPPY ? "fratboys" : "hippies");

    String message =
        "You are about to defeat the last "
            + side1
            + " and open the way to their camp. However, you have not yet finished with the "
            + side2
            + ". If you are sure you don't want the Order of the Silver Wossname, click on the image and proceed.";
    this.sendGeneralWarning("wossname.gif", message, Confirm.WOSSNAME);

    return true;
  }

  private boolean sendFamiliarWarning() {
    if (this.getFormField(Confirm.FAMILIAR) != null) {
      return false;
    }

    if (!KoLCharacter.getPath().canUseFamiliars()
        || KoLCharacter.inPokefam()
        || KoLCharacter.inQuantum()) {
      return false;
    }

    // Check for a 100% familiar run if the current familiar has zero combat experience.
    FamiliarData familiar = KoLCharacter.getFamiliar();

    if (!familiar.isUnexpectedFamiliar()) {
      return false;
    }

    // It's OK once we free the king unless we are in Bad Moon with a black cat
    if (KoLCharacter.kingLiberated()
        && (!KoLCharacter.inBadMoon() || familiar.getId() != FamiliarPool.BLACK_CAT)) {
      return false;
    }

    StringBuilder warning = new StringBuilder();

    warning.append("<html><head><script language=Javascript src=\"/");
    warning.append(KoLConstants.BASICS_JS);
    warning.append("\"></script>");

    warning.append(
        "<link rel=\"stylesheet\" type=\"text/css\" href=\"/images/styles.css\"></head>");
    warning.append(
        "<body><center><table width=95%	 cellspacing=0 cellpadding=0><tr><td style=\"color: white;\" align=center bgcolor=blue><b>Results:</b></td></tr><tr><td style=\"padding: 5px; border: 1px solid blue;\"><center><table><tr><td><center>");

    warning.append("<table><tr>");

    String url = this.getURLString();

    // Proceed with familiar
    warning.append(
        "<td align=center valign=center><div id=\"lucky\" style=\"padding: 4px 4px 4px 4px\"><a style=\"text-decoration: none\" href=\"");
    warning.append(url);
    warning.append(!url.contains("?") ? "?" : "&");
    warning.append(Confirm.FAMILIAR);
    warning.append("=on\"><img src=\"/images/");

    if (familiar.getId() != FamiliarData.NO_FAMILIAR.getId()) {
      warning.append("itemimages/");
    }

    warning.append(FamiliarDatabase.getFamiliarImageLocation(familiar.getId()));
    warning.append("\" width=30 height=30 border=0>");
    warning.append("</a></div></td>");

    warning.append("<td>&nbsp;&nbsp;&nbsp;&nbsp;</td>");

    // Protect familiar
    warning.append(
        "<td align=center valign=center><div id=\"unlucky\" style=\"padding: 4px 4px 4px 4px\">");

    warning.append(
        "<a style=\"text-decoration: none\" href=\"#\" onClick=\"singleUse('familiar.php', 'action=newfam&ajax=1&newfam=");
    warning.append(FamiliarData.getSingleFamiliarRun());
    warning.append("'); void(0);\">");
    warning.append("<img src=\"/images/");

    if (FamiliarData.getSingleFamiliarRun() != FamiliarData.NO_FAMILIAR.getId()) {
      warning.append("itemimages/");
    }

    warning.append(FamiliarDatabase.getFamiliarImageLocation(FamiliarData.getSingleFamiliarRun()));
    warning.append("\" width=30 height=30 border=0>");
    warning.append("</a></div></td>");

    warning.append(
        "</tr></table></center><blockquote>KoLmafia has detected that you may be doing a 100% familiar run.  If you are sure you wish to deviate from this path, click on the familiar on the left.  If this was an accident, please click on the familiar on the right to switch to your 100% familiar.");
    warning.append(
        "</blockquote></td></tr></table></center></td></tr></table></center></body></html>");

    this.pseudoResponse("HTTP/1.1 200 OK", warning.toString());

    return true;
  }

  boolean sendKungFuWarning() {
    if (this.getFormField(Confirm.KUNGFU) != null) {
      return false;
    }

    // If you don't have the first Kung Fu intrinsic active, there's nothing to warn about
    int index = KoLConstants.activeEffects.indexOf(EffectPool.get(EffectPool.KUNG_FU_FIGHTING));
    if (index == -1 || KoLConstants.activeEffects.get(index).getCount() < Integer.MAX_VALUE) {
      return false;
    }

    // If your hands are empty, there's nothing to warn about
    if (EquipmentManager.getEquipment(Slot.WEAPON).equals(EquipmentRequest.UNEQUIP)
        && EquipmentManager.getEquipment(Slot.OFFHAND).equals(EquipmentRequest.UNEQUIP)) {
      return false;
    }

    StringBuilder warning = new StringBuilder();

    warning.append("<html><head><script language=Javascript src=\"/");
    warning.append(KoLConstants.BASICS_JS);
    warning.append("\"></script>");

    warning.append(
        "<link rel=\"stylesheet\" type=\"text/css\" href=\"/images/styles.css\"></head>");
    warning.append(
        "<body><center><table width=95%	 cellspacing=0 cellpadding=0><tr><td style=\"color: white;\" align=center bgcolor=blue><b>Results:</b></td></tr><tr><td style=\"padding: 5px; border: 1px solid blue;\"><center><table><tr><td><center>");

    warning.append("<table><tr>");

    String url = this.getURLString();

    // Proceed with your hands full
    warning.append(
        "<td align=center valign=center><div id=\"lucky\" style=\"padding: 4px 4px 4px 4px\"><a style=\"text-decoration: none\" href=\"");
    warning.append(url);
    warning.append(!url.contains("?") ? "?" : "&");
    warning.append(Confirm.KUNGFU);
    warning.append("=on\"><img src=\"/images/itemimages/kungfu.gif");
    warning.append("\" width=30 height=30 border=0>");
    warning.append("</a></div></td>");

    warning.append("<td>&nbsp;&nbsp;&nbsp;&nbsp;</td>");

    // Empty your hands
    warning.append(
        "<td align=center valign=center><div id=\"unlucky\" style=\"padding: 4px 4px 4px 4px\">");

    RelayRequest.redirectedCommandURL = "/inventory.php?which=2";

    warning.append(
        "<a style=\"text-decoration: none\" href=\"/KoLmafia/redirectedCommand?cmd=unequip+weapon;unequip+offhand&pwd=");
    warning.append(GenericRequest.passwordHash);
    warning.append("\">");
    warning.append("<img src=\"/images/itemimages/hand.gif");
    warning.append("\" width=30 height=30 border=0>");
    warning.append("</a></div></td>");

    warning.append(
        "</tr></table></center><blockquote>KoLmafia has detected that you are about to lose Kung Fu buffs.  ");
    warning.append(
        "If you are sure you wish to lose these buffs, click the icon on the left to adventure.  If this was an accident, ");
    warning.append("click the icon on the right to empty your hands.");
    warning.append(
        "</blockquote></td></tr></table></center></td></tr></table></center></body></html>");

    this.pseudoResponse("HTTP/1.1 200 OK", warning.toString());

    return true;
  }

  private boolean sendBossWarning(final String path, final KoLAdventure adventure) {
    // Sometimes, people want the MCD rewards from various boss monsters.
    // Let's help out.

    // No MCD if the Kingdom exploded
    if (KoLCharacter.isKingdomOfExploathing()) {
      return false;
    }

    // This one's for Baron von Ratsworth, who has special items at 2 and 9.
    if (path.startsWith("cellar.php")) {
      String action = this.getFormField("action");
      String square = this.getFormField("whichspot");
      String baron = TavernManager.baronSquare();
      if (action != null
          && action.equals("explore")
          && square != null
          && square.equals(baron)
          && KoLCharacter.mcdAvailable()) {
        return this.sendBossWarning(
            "Baron von Ratsworth",
            "ratsworth.gif",
            "Baron von Ratsworth's monocle",
            2,
            "Baron von Ratsworth's money clip",
            9,
            "Baron von Ratsworth's tophat");
      }
      return false;
    }

    // Some paths replace the usual bosses with other monsters
    if (KoLCharacter.inRaincore()
        || KoLCharacter.isVampyre()
        || KoLCharacter.isPlumber()
        || KoLCharacter.inRobocore()
        || KoLCharacter.inDinocore()
        || KoLCharacter.inShadowsOverLoathing()) {
      return false;
    }

    // This one's for the Boss Bat, who has special items at 4 and 8.
    if (path.startsWith("adventure.php")) {
      int location = adventure == null ? 0 : adventure.getAdventureNumber();

      if (location == AdventurePool.BOSSBAT && KoLCharacter.mcdAvailable()) {
        int turns = AdventureSpentDatabase.getTurns("The Boss Bat's Lair");
        if (turns < 4) {
          // Do not prompt about adjusting the MCD if the Boss Bat cannot show up
          return false;
        }
        return this.sendBossWarning(
            "The Boss Bat", "bossbat.gif", "", 4, "Boss Bat britches", 8, "Boss Bat bling");
      }
      return false;
    }

    // This one is for the Knob Goblin King, who has special items at 3 and 7.
    if (path.startsWith("cobbsknob.php")) {
      String action = this.getFormField("action");
      if (action != null && action.equals("throneroom") && KoLCharacter.mcdAvailable()) {
        return this.sendBossWarning(
            "The Knob Goblin King",
            "goblinking.gif",
            "Crown of the Goblin King",
            3,
            "Glass Balls of the Goblin King",
            7,
            "Codpiece of the Goblin King");
      }
      return false;
    }

    // This one is for the Bonerdagon, who has special items at 5 and 10.
    if (path.startsWith("crypt.php")) {
      String action = this.getFormField("action");
      if (action != null && action.equals("heart") && KoLCharacter.mcdAvailable()) {
        return this.sendBossWarning(
            "The Bonerdagon",
            "bonedragon.gif",
            "",
            5,
            "rib of the Bonerdagon",
            10,
            "vertebra of the Bonerdagon");
      }
      return false;
    }

    return false;
  }

  private String prunedItemModifiers(final int itemId) {
    // Perform the same adjustments to the displayed modifiers as
    // the Gear Changer, except leave Familiar Effect if you own
    // the relevant familiar.
    ModifierList list = ModifierDatabase.getModifierList(new Lookup(ModifierType.ITEM, itemId));
    list.removeModifier("Wiki Name");
    list.removeModifier("Modifiers");
    list.removeModifier("Outfit");
    ConsumptionType type = ItemDatabase.getConsumptionType(itemId);
    if (!(type == ConsumptionType.HAT && KoLCharacter.usableFamiliar(FamiliarPool.HATRACK) != null)
        && !(type == ConsumptionType.PANTS
            && KoLCharacter.usableFamiliar(FamiliarPool.SCARECROW) != null)) {
      list.removeModifier("Familiar Effect");
    }
    String stringform = list.toString();
    stringform = StringUtilities.globalStringReplace(stringform, "Familiar", "Fam");
    stringform = StringUtilities.globalStringReplace(stringform, "Experience", "Exp");
    stringform = StringUtilities.globalStringReplace(stringform, "Damage", "Dmg");
    stringform = StringUtilities.globalStringReplace(stringform, "Resistance", "Res");
    stringform = StringUtilities.globalStringReplace(stringform, "Percent", "%");
    return stringform;
  }

  private boolean sendBossWarning(
      final String name,
      final String image,
      final String item0,
      final int mcd1,
      final String item1,
      final int mcd2,
      final String item2) {
    if (KoLCharacter.inPokefam()) {
      // Jerry Bradford replaces all the bosses; you cannot
      // use the MCD to select your reword
      return false;
    }

    if (this.getFormField(Confirm.MCD) != null) {
      return false;
    }

    int mcd0 = KoLCharacter.getMindControlLevel();

    int item0Id = (item0 == null) ? -1 : ItemDatabase.getItemId(item0, 1, false);
    String item0Image = (item0 == null) ? "" : ItemDatabase.getItemImageLocation(item0Id);
    String item0Modifiers = (item0 == null) ? "" : prunedItemModifiers(item0Id);
    int item1Id = ItemDatabase.getItemId(item1, 1, false);
    String item1Image = (item1 == null) ? "" : ItemDatabase.getItemImageLocation(item1Id);
    String item1Modifiers = (item1 == null) ? "" : prunedItemModifiers(item1Id);
    int item2Id = ItemDatabase.getItemId(item2, 1, false);
    String item2Image = (item2 == null) ? "" : ItemDatabase.getItemImageLocation(item2Id);
    String item2Modifiers = (item2 == null) ? "" : prunedItemModifiers(item2Id);

    StringBuilder warning = new StringBuilder();

    warning.append("<html><head><script language=Javascript src=\"/");
    warning.append(KoLConstants.BASICS_JS);
    warning.append("\"></script>");

    warning.append("<script language=Javascript> ");
    warning.append("var default0 = ").append(mcd0).append("; ");
    warning.append("var default1 = ").append(mcd1).append("; ");
    warning.append("var default2 = ").append(mcd2).append("; ");
    warning.append("var current = ").append(mcd0).append("; ");
    warning.append("function switchLinks( id ) { ");
    warning.append("if ( id == \"mcd1\" ) { ");
    warning.append("current = (current == default0) ? default1 : default0; ");
    warning.append("} else { ");
    warning.append("current = (current == default0) ? default2 : default0; ");
    warning.append("} ");
    warning.append(
        "getObject(\"mcd1\").style.border = (current == default1) ? \"1px dashed blue\" : \"1px dashed white\"; ");
    warning.append(
        "getObject(\"mcd2\").style.border = (current == default2) ? \"1px dashed blue\" : \"1px dashed white\"; ");
    warning.append(
        "top.charpane.location.href = \"/KoLmafia/sideCommand?cmd=mcd+\" + current + \"&pwd=");
    warning.append(GenericRequest.passwordHash);
    warning.append("\"; } </script>");

    warning.append(
        "<link rel=\"stylesheet\" type=\"text/css\" href=\"/images/styles.css\"></head>");
    warning.append(
        "<body><center><table width=95%	 cellspacing=0 cellpadding=0><tr><td style=\"color: white;\" align=center bgcolor=blue><b>Results:</b></td></tr><tr><td style=\"padding: 5px; border: 1px solid blue;\"><center><table><tr><td><center>");

    warning.append("<table><tr>");

    warning.append("<td align=center valign=top><div id=\"mcd1\" style=\"padding: 4px 4px 4px 4px");

    if (mcd0 == mcd1) {
      warning.append("; border: 1px dashed blue");
    }

    warning.append("\">");
    warning.append("<table ncol=1 width=100>");
    warning.append(
        "<tr align=center><td><a id=\"link1\" style=\"text-decoration: none\" onClick=\"switchLinks('mcd1'); void(0);\" href=\"#\"><img src=\"/images/itemimages/");
    warning.append(item1Image);
    warning.append("\" width=30 height=30><br /><font size=1>MCD ");
    warning.append(mcd1);
    warning.append("</font></a></td></tr>");
    if (!item1Modifiers.equals("")) {
      warning.append("<tr align=center><td><font size=1>");
      warning.append(item1Modifiers);
      warning.append("</font></td></tr>");
    }
    warning.append("</table>");
    warning.append("</div></td>");

    warning.append("<td>&nbsp;&nbsp;&nbsp;&nbsp;</td>");

    warning.append("<td valign=top>");
    warning.append("<table ncol=1 width=100>");
    warning.append("<tr align=center><td><a href=\"");
    warning.append(this.getURLString());
    warning.append("&");
    warning.append(Confirm.MCD);
    warning.append("=on");
    warning.append("\"><img src=\"/images/adventureimages/");
    warning.append(image);
    warning.append("\"></a>");
    warning.append("</td></tr>");

    if (!item0.equals("")) {
      warning.append("<tr align=center><td><img src=\"/images/itemimages/");
      warning.append(item0Image);
      warning.append("\" width=30 height=30></td></tr>");
      warning.append("<tr align=center><td><font size=1>");
      warning.append(item0Modifiers);
      warning.append("</font></td></tr>");
    }
    warning.append("</table>");
    warning.append("</td>");

    warning.append("<td>&nbsp;&nbsp;&nbsp;&nbsp;</td>");

    warning.append("<td align=center valign=top><div id=\"mcd2\" style=\"padding: 4px 4px 4px 4px");

    if (KoLCharacter.getMindControlLevel() == mcd2) {
      warning.append("; border: 1px dashed blue");
    }

    warning.append("\">");
    warning.append("<table ncol=1 width=100>");
    warning.append(
        "<tr align=center><td><a id=\"link2\" style=\"text-decoration: none\" onClick=\"switchLinks('mcd2'); void(0);\" href=\"#\"><img src=\"/images/itemimages/");
    warning.append(item2Image);
    warning.append("\" width=30 height=30><br /><font size=1>MCD ");
    warning.append(mcd2);
    warning.append("</font></a></td></tr>");
    if (!item2Modifiers.equals("")) {
      warning.append("<tr align=center><td><font size=1>");
      warning.append(item2Modifiers);
      warning.append("</font></td></tr>");
    }
    warning.append("</table>");
    warning.append("</div></td>");

    warning.append("</tr></table></center><blockquote>");
    warning.append(name);

    warning.append(
        " drops special rewards based on your mind-control level.  If you'd like a special reward, click on one of the items above to set your mind-control device appropriately.  Click on it again to reset the MCD back to your old setting.	 Click on ");
    warning.append(name);

    warning.append(
        " once you've decided to proceed.</blockquote></td></tr></table></center></td></tr></table></center></body></html>");

    this.pseudoResponse("HTTP/1.1 200 OK", warning.toString());

    return true;
  }

  private boolean sendSorceressWarning(final String urlString) {
    // If the person is visiting the sorceress and they
    // forgot to make the Wand, remind them.

    if (!urlString.contains("whichplace=nstower")) {
      // We are not even in the tower.
      return false;
    }

    if (urlString.contains(Confirm.SORCERESS.toString())) {
      return false;
    }

    // Some paths don't need a wand
    if (KoLCharacter.inAxecore()
        || KoLCharacter.inZombiecore()
        || KoLCharacter.isJarlsberg()
        || KoLCharacter.inHighschool()
        || KoLCharacter.isSneakyPete()
        || KoLCharacter.inRaincore()
        || KoLCharacter.isEd()
        || KoLCharacter.inTheSource()
        || KoLCharacter.inBondcore()
        || KoLCharacter.inPokefam()
        || KoLCharacter.isVampyre()
        || KoLCharacter.isPlumber()
        || KoLCharacter.inRobocore()
        || KoLCharacter.inFirecore()
        || KoLCharacter.inDinocore()
        || KoLCharacter.inShadowsOverLoathing()
        || KoLCharacter.inWereProfessor()) {
      return false;
    }

    // You can gain the Confidence! intrinsic at action=ns_08_monster4
    //
    // The Shadow is action=ns_09_monster5
    // The Sorceress is action=ns_10_sorcfight
    //
    // If you adventure outside the tower - to get wand or items
    // for the shadow, for example - you lose the intrinsic.
    //
    // Therefore, give the warning before you have the option to
    // get the intrinsic, as well as before the sorceress herself.

    if (!urlString.contains("action=ns_08_monster4")
        && !urlString.contains("action=ns_10_sorcfight")) {
      return false;
    }

    StringBuilder warning = new StringBuilder();
    warning.append(
        "It's possible there is something very important you're forgetting to do.<br>You lack:");

    // You need the Antique hand mirror in Beecore
    if (KoLCharacter.inBeecore()) {
      if (InventoryManager.retrieveItem(ItemPool.ANTIQUE_HAND_MIRROR)) {
        return false;
      }

      warning.append(" <span title=\"Bedroom\">Antique hand mirror</span>");
      this.sendGeneralWarning("handmirror.gif", warning.toString(), Confirm.SORCERESS);
      return true;
    }

    // Otherwise, you need the Wand of Nagamar
    if (KoLCharacter.hasEquipped(SorceressLairManager.NAGAMAR)
        || InventoryManager.hasItem(SorceressLairManager.NAGAMAR)) {
      return false;
    }

    if (Preferences.getBoolean("autoCraft")
        && InventoryManager.retrieveItem(SorceressLairManager.NAGAMAR)) {
      return false;
    }

    // Give him options to go farm for what he is missing

    if (!InventoryManager.hasItem(ItemPool.WA)) {
      if (!InventoryManager.hasItem(ItemPool.RUBY_W)) {
        warning.append(" <span title=\"Friar's Gate\">W</span>");
      }
      if (!InventoryManager.hasItem(ItemPool.METALLIC_A)) {
        warning.append(" <span title=\"Airship\">A</span>");
      }
    }

    if (!InventoryManager.hasItem(ItemPool.ND)) {
      if (!InventoryManager.hasItem(ItemPool.LOWERCASE_N)) {
        if (!InventoryManager.hasItem(ItemPool.NG)) {
          warning.append(" <span title=\"Orc Chasm\">N</span>");
        } else {
          warning.append(" N (untinker your NG)");
        }
      }
      if (!InventoryManager.hasItem(ItemPool.HEAVY_D)) {
        warning.append(" <span title=\"Castle\">D</span>");
      }
    }

    this.sendGeneralWarning("wand.gif", warning.toString(), Confirm.SORCERESS);
    return true;
  }

  private boolean sendArcadeWarning() {

    if (this.getFormField(Confirm.ARCADE) != null) {
      return false;
    }

    String action = this.getFormField("action");
    if (action == null || !action.equals("game")) {
      return false;
    }

    String game = this.getFormField("whichgame");
    if (game == null || !game.equals("2")) {
      return false;
    }

    int power = EquipmentDatabase.getPower(EquipmentManager.getEquipment(Slot.WEAPON).getItemId());
    if (power <= 50
        || power >= 150
        || EquipmentManager.getEquipment(Slot.HAT) == EquipmentRequest.UNEQUIP
        || EquipmentManager.getEquipment(Slot.PANTS) == EquipmentRequest.UNEQUIP) {
      this.sendGeneralWarning(
          "ggtoken.gif",
          "You might not be properly equipped to play this game.<br>Click the token if you'd like to continue anyway.",
          Confirm.ARCADE);
      return true;
    }

    return false;
  }

  public boolean sendWineglassWarning(final KoLAdventure adventure) {
    if (adventure == null) {
      return false;
    }

    if (this.getFormField(Confirm.WINEGLASS) != null) {
      return false;
    }

    if (!adventure.tooDrunkToAdventure()) {
      return false;
    }

    // These don't need warning as they don't send you to a Drunken Stupor
    if (!adventure.hasSnarfblat()) {
      return false;
    }

    // If it is is not in inventory, nothing we can do
    if (InventoryManager.getCount(ItemPool.DRUNKULA_WINEGLASS) == 0) {
      return false;
    }

    if (!EquipmentManager.canEquip(ItemPool.DRUNKULA_WINEGLASS)) {
      return false;
    }

    String warning =
        "KoLmafia has detected that you are about to adventure while overdrunk. "
            + "If you are sure you wish to adventure in a Drunken Stupor, click the icon on the left to adventure. "
            + "If this was an accident, click the icon in the center to equip Drunkula's wineglass. "
            + "If you want to adventure in a Drunken Stupor and not be nagged, click the icon on the right to closet Drunkula's wineglass.";
    this.sendOptionalWarning(
        Confirm.WINEGLASS,
        warning,
        "hand.gif",
        "dr_wineglass.gif",
        "\"#\" onClick=\"singleUse('inv_equip.php','which=2&action=equip&whichitem="
            + ItemPool.DRUNKULA_WINEGLASS
            + "&pwd="
            + GenericRequest.passwordHash
            + "&ajax=1');void(0);\"",
        "/images/closet.gif",
        "\"#\" onClick=\"singleUse('fillcloset.php','action=closetpush&whichitem="
            + ItemPool.DRUNKULA_WINEGLASS
            + "&qty=all&pwd="
            + GenericRequest.passwordHash
            + "&ajax=1');void(0);\"");

    return true;
  }

  private boolean sendOverdrunkAdventureWarning(final KoLAdventure adventure) {
    if (adventure == null) {
      return false;
    }

    // Don't warn again if you've already said ok to Wineglass warning about overdrunk (or this one)
    if (this.getFormField(Confirm.OVERDRUNK_ADVENTURE) != null
        || this.getFormField(Confirm.WINEGLASS) != null) {
      return false;
    }

    // If you are not overdrunk, nothing to warn about
    if (!KoLCharacter.isFallingDown()) {
      return false;
    }

    // If you are equipped with Drunkula's wineglass, nothing to warn about
    if (KoLCharacter.hasEquipped(ItemPool.DRUNKULA_WINEGLASS, Slot.OFFHAND)
        || KoLCharacter.hasEquipped(ItemPool.DRUNKULA_WINEGLASS, Slot.FAMILIAR)) {
      return false;
    }

    // Only adventure.php will shunt you into a Drunken Stupor
    if (!adventure.getFormSource().equals("adventure.php")) {
      return false;
    }

    String warning =
        "KoLmafia has detected that you are about to adventure while overdrunk. "
            + "If you are sure you wish to adventure in a Drunken Stupor, click the icon to adventure. ";
    this.sendGeneralWarning("martini.gif", warning, Confirm.OVERDRUNK_ADVENTURE);
    return true;
  }

  private boolean sendSpelunkyWarning(final KoLAdventure adventure) {
    // If this is not an adventure URL, nothing to do here
    if (adventure == null) {
      return false;
    }

    // If the player doesn't want Spelunky hints, punt
    if (!Preferences.getBoolean("spelunkyHints")) {
      return false;
    }

    // If the game is over, nothing to warn about
    if (SpelunkyRequest.getTurnsLeft() == 0) {
      return false;
    }

    // If the player has said he doesn't care about this warning, cool
    if (this.getFormField(Confirm.SPELUNKY) != null) {
      return false;
    }

    // Depending on noncombat phase step and/or adventure location,
    // craft an appropriate warning
    String message = SpelunkyRequest.spelunkyWarning(adventure, Confirm.SPELUNKY);
    if (message != null) {
      String image = SpelunkyRequest.adventureImage(adventure);
      this.sendGeneralWarning(image != null ? image : "spelwhip.gif", message, Confirm.SPELUNKY);
      return true;
    }

    return false;
  }

  public void sendGeneralWarning(final String image, final String message, final Confirm confirm) {
    this.sendGeneralWarning(image, message, confirm, null, false);
  }

  public void sendGeneralWarning(
      final String image,
      final String message,
      final Confirm confirm,
      final boolean usePostMethod) {
    this.sendGeneralWarning(image, message, confirm, null, usePostMethod);
  }

  public void sendGeneralWarning(
      final String image, final String message, final Confirm confirm, final String extra) {
    this.sendGeneralWarning(image, message, confirm, extra, false);
  }

  public void sendGeneralWarning(
      final String image,
      final String message,
      final Confirm confirm,
      final String extra,
      final boolean usePostMethod) {
    // Save for testing
    this.lastWarning = message;

    StringBuilder warning = new StringBuilder();

    warning.append(
        "<html><head><link rel=\"stylesheet\" type=\"text/css\" href=\"/images/styles.css\"><script language=\"Javascript\" src=\"/");
    warning.append(KoLConstants.BASICS_JS);
    warning.append(
        "\"></script></head><body><center><table width=95%  cellspacing=0 cellpadding=0><tr><td style=\"color: white;\" align=center bgcolor=blue><b>Results:</b></td></tr><tr><td style=\"padding: 5px; border: 1px solid blue;\"><center><table><tr><td>");

    if (image != null && !image.equals("")) {
      warning.append("<center>");
      if (usePostMethod) {
        // We need to craft a form
        warning.append("<form method=\"post\" name=warningform action=\"");
        warning.append(this.getBasePath());
        warning.append("\">");
        for (String element : this.data) {
          int equals = element.indexOf("=");
          String name = equals == -1 ? element : element.substring(0, equals);
          String value = equals == -1 ? "" : element.substring(equals + 1);
          warning.append("<input type=hidden name=");
          warning.append(name);
          warning.append(" value=\"");
          warning.append(value);
          warning.append("\">");
        }
        warning.append("<input type=hidden name=");
        warning.append(confirm);
        warning.append(" value=\"");
        warning.append("on");
        warning.append("\">");
        warning.append("<input type=\"image\" src=\"/images/");
        if (!image.contains("/")) {
          warning.append("itemimages/");
        }
        warning.append(image);
        warning.append("\"");
        if (!image.contains("/")) {
          warning.append(" width=30 height=30");
        }
        warning.append(">");
        warning.append("</form>");
      } else {
        if (confirm != null) {
          String url = this.getFullURLString();
          warning.append("<a href=\"");
          warning.append(url);
          warning.append(!url.contains("?") ? "?" : "&");
          warning.append(confirm);
          warning.append("=on");
          if (extra != null) {
            warning.append("&");
            warning.append(extra);
          }
          warning.append("\">");
        }
        warning.append("<img id=\"warningImage\" src=\"/images/");
        if (!image.contains("/")) {
          warning.append("itemimages/");
        }
        warning.append(image);
        warning.append("\"");
        if (!image.contains("/")) {
          warning.append(" width=30 height=30");
        }
        warning.append(">");
        if (confirm != null) {
          warning.append("</a>");
        }
      }
      warning.append("<br></center>");
    }

    warning.append("<blockquote>");
    warning.append(message);
    warning.append(
        "</blockquote></td></tr></table></center></td></tr></table></center></body></html>");

    this.pseudoResponse("HTTP/1.1 200 OK", warning.toString());
  }

  public void sendOptionalWarning(
      final Confirm confirm,
      final String message,
      final String image1,
      final String image2,
      final String action2,
      final String image3,
      final String action3) {
    // Save for testing
    this.lastWarning = message;

    StringBuilder warning = new StringBuilder();

    warning.append("<html><head><script language=Javascript src=\"/");
    warning.append(KoLConstants.BASICS_JS);
    warning.append("\"></script>");

    warning.append(
        "<link rel=\"stylesheet\" type=\"text/css\" href=\"/images/styles.css\"></head>");
    warning.append(
        "<body><center><table width=95%	 cellspacing=0 cellpadding=0><tr><td style=\"color: white;\" align=center bgcolor=blue><b>Results:</b></td></tr><tr><td style=\"padding: 5px; border: 1px solid blue;\"><center><table><tr><td><center>");

    warning.append("<table><tr>");

    String url = this.getURLString();

    if (image1 != null && !image1.equals("")) {
      // first image is proceed with adventure
      warning.append(
          "<td align=center valign=center><div id=\"proceed\" style=\"padding: 4px 4px 4px 4px\"><a style=\"text-decoration: none\" href=\"");
      warning.append(url);
      warning.append(!url.contains("?") ? "?" : "&");
      warning.append(confirm);
      warning.append("=on\"><img src=\"");
      if (!image1.startsWith("/")) {
        warning.append("/images/itemimages/");
      }
      warning.append(image1);
      warning.append("\" width=30 height=30 border=0>");
      warning.append("</a></div></td>");
    }

    if (image2 != null && !image2.equals("")) {
      warning.append("<td>&nbsp;&nbsp;&nbsp;&nbsp;</td>");
      // perform optional action, do not (yet) adventure.
      warning.append(
          "<td align=center valign=center><div id=\"optionalAction1\" style=\"padding: 4px 4px 4px 4px\">");
      warning.append("<a style=\"text-decoration: none\" href=");
      warning.append(action2);
      warning.append("><img src=\"");
      if (!image2.startsWith("/")) {
        warning.append("/images/itemimages/");
      }
      warning.append(image2);
      warning.append("\" width=30 height=30 border=0>");
      warning.append("</a></div></td>");
    }

    if (image3 != null && !image3.equals("")) {
      warning.append("<td>&nbsp;&nbsp;&nbsp;&nbsp;</td>");
      // perform optional action, do not (yet) adventure.
      warning.append(
          "<td align=center valign=center><div id=\"optionalAction2\" style=\"padding: 4px 4px 4px 4px\">");
      warning.append("<a style=\"text-decoration: none\" href=");
      warning.append(action3);
      warning.append("><img src=\"");
      if (!image3.startsWith("/")) {
        warning.append("/images/itemimages/");
      }
      warning.append(image3);
      warning.append("\" width=30 height=30 border=0>");
      warning.append("</a></div></td>");
    }

    warning.append("</tr></table></center><blockquote>");
    warning.append(message);
    warning.append(
        "</blockquote></td></tr></table></center></td></tr></table></center></body></html>");

    this.pseudoResponse("HTTP/1.1 200 OK", warning.toString());
  }

  private void handleSafety() {
    if (RelayRequest.lastSafety == null) {
      this.pseudoResponse("HTTP/1.1 200 OK", "");
      return;
    }

    AreaCombatData combat = RelayRequest.lastSafety.getAreaSummary();

    if (combat == null) {
      this.pseudoResponse("HTTP/1.1 200 OK", "");
      return;
    }

    StringBuilder buffer = new StringBuilder();
    buffer.append("<table width=\"100%\"><tr><td align=left valign=top><font size=3>");
    buffer.append(RelayRequest.lastSafety.getAdventureName());
    buffer.append("</font></td><td align=right valign=top><font size=2>");

    buffer.append("<a style=\"text-decoration: none\" href=\"javascript: ");

    buffer.append("var safety; if ( document.getElementById ) ");
    buffer.append("safety = top.chatpane.document.getElementById( 'safety' ); ");
    buffer.append("else if ( document.all ) ");
    buffer.append("safety = top.chatpane.document.all[ 'safety' ]; ");

    buffer.append("safety.closed = true;");
    buffer.append("safety.active = false;");
    buffer.append("safety.style.display = 'none'; ");
    buffer.append("var nodes = top.chatpane.document.body.childNodes; ");
    buffer.append("for ( var i = 0; i < nodes.length; ++i ) ");
    buffer.append("if ( nodes[i].style && nodes[i].id != 'safety' ) ");
    buffer.append("nodes[i].style.display = nodes[i].unsafety; ");

    buffer.append("void(0);\">x</a></font></td></tr></table>");
    buffer.append("<br/><font size=2>");

    String combatData = combat.toString(true);
    combatData = combatData.substring(6, combatData.length() - 7);
    buffer.append(combatData);

    buffer.append("</font>");
    this.pseudoResponse("HTTP/1.1 200 OK", buffer.toString());
  }

  private void handleCommand() {
    // None of the above checks wound up happening. So, do some
    // special handling, catching any exceptions that happen to
    // popup along the way.

    String pwd = this.getFormField("pwd");
    if (pwd == null || !pwd.equals((GenericRequest.passwordHash))) {
      this.pseudoResponse("HTTP/1.1 401 Unauthorized", "");
      return;
    }

    String path = this.getBasePath();
    if (path.endsWith("submitCommand")) {
      submitCommand(this.getFormField("cmd", false));
      this.pseudoResponse("HTTP/1.1 200 OK", "");
    } else if (path.endsWith("redirectedCommand")) {
      boolean polled = path.endsWith("polledredirectedCommand");
      String cmd = this.getFormField("cmd");
      if (!polled || !cmd.equals("wait")) {
        RelayRequest.specialCommandStatus = "";
        submitCommand(cmd, false, !polled);
      }
      if (!polled || !pollForCompletion("polledredirectedCommand")) {
        this.pseudoResponse("HTTP/1.1 302 Found", RelayRequest.redirectedCommandURL);
      }
    } else if (path.endsWith("sideCommand")) {
      submitCommand(this.getFormField("cmd", false), true);
      this.pseudoResponse("HTTP/1.1 302 Found", "/charpane.php");
    } else if (path.endsWith("specialCommand")
        || path.endsWith("waitSpecialCommand")
        || path.endsWith("parameterizedCommand")) {
      String cmd = this.getFormField("cmd");
      if (!cmd.equals("wait")) {
        RelayRequest.specialCommandIsAdventure = false;
        RelayRequest.specialCommandResponse = "";
        RelayRequest.specialCommandStatus = "";

        if (path.endsWith("parameterizedCommand")) {
          String commandURL = this.getURLString();
          int pwdStart = commandURL.indexOf("pwd");
          int pwdEnd = commandURL.indexOf("&", pwdStart);

          String parameters = pwdEnd == -1 ? "" : commandURL.substring(pwdEnd + 1);
          submitCommand(cmd + " " + parameters);
        } else {
          boolean wait = path.endsWith("waitSpecialCommand");
          submitCommand(cmd, false, wait);
        }
      }

      this.contentType = "text/html";
      if (pollForCompletion("specialCommand")) {
      } else if (RelayRequest.specialCommandResponse.length() > 0) {
        StringBuilder buffer = new StringBuilder();

        Matcher matcher =
            RelayRequest.BASE_LINK_PATTERN.matcher(RelayRequest.specialCommandResponse);

        while (matcher.find()) {
          String location = matcher.group(4);

          if (location.startsWith("http") || location.startsWith("javascript")) {
            matcher.appendReplacement(buffer, "$0");
          } else {
            matcher.appendReplacement(buffer, "$1$2$3/$4");
          }
        }

        matcher.appendTail(buffer);

        int headIndex = buffer.indexOf("</head>");
        if (headIndex != -1) {
          buffer.insert(headIndex, "<base href=\"/\">");
        }

        this.pseudoResponse("HTTP/1.1 200 OK", buffer.toString());

        RelayRequest.specialCommandResponse = "";
        RelayRequest.specialCommandStatus = "";
        if (RelayRequest.specialCommandIsAdventure) {
          RelayRequest.executeAfterAdventureScript();
          RelayRequest.specialCommandIsAdventure = false;
        }
      } else {
        // specialCommand invoked for command that doesn't
        // specifically support it - we have no page to display.
        this.pseudoResponse("HTTP/1.1 200 OK", "<html><body>Automation complete.</body></html>)");
      }
    } else if (path.endsWith("jsonApi")) {
      this.handleJsonApi(this.getFormField("body"));
    } else if (path.endsWith("logout")) {
      submitCommand("logout");
      this.pseudoResponse("HTTP/1.1 302 Found", "/loggedout.php");
    } else if (path.endsWith("messageUpdate")) {
      this.pseudoResponse("HTTP/1.1 200 OK", RelayServer.getNewStatusMessages());
    } else if (path.endsWith("lookupLocation")) {
      RelayRequest.lastSafety =
          AdventureDatabase.getAdventureByURL(
              "adventure.php?snarfblat=" + this.getFormField("snarfblat"));
      AdventureFrame.updateSelectedAdventure(RelayRequest.lastSafety);
      this.handleSafety();
    } else if (path.endsWith("updateLocation")) {
      this.handleSafety();
    } else {
      this.pseudoResponse("HTTP/1.1 200 OK", "");
    }
  }

  public boolean pollForCompletion(String type) {
    if (!CommandDisplayFrame.hasQueuedCommands()) {
      return false;
    }

    String refreshURL = "/KoLmafia/" + type + "?cmd=wait&pwd=" + GenericRequest.passwordHash;
    String buffer =
        "<html><head>"
            + "<meta http-equiv=\"refresh\" content=\"1; URL="
            + refreshURL
            + "\">"
            + "</head><body>"
            + "<a href=\""
            + refreshURL
            + "\">"
            + "Automating (see CLI for details, click to refresh)..."
            + "</a><p>"
            + RelayRequest.specialCommandStatus
            + "</p></body></html>";

    this.contentType = "text/html";
    this.pseudoResponse("HTTP/1.1 200 OK", buffer);
    return true;
  }

  private void jsonError(String message) {
    this.statusLine = "HTTP/1.1 400 Bad Request";
    this.contentType = "application/json";
    this.responseCode = 400;
    this.responseText = JSON.toJSONString(Map.of("error", message));
  }

  private String underscoreName(String name) {
    return name.replaceAll("[A-Z]", "_$0").toLowerCase();
  }

  /**
   * Handle JSON API request.
   *
   * <p>API is, as usual, x-www-urlencoded with pwd=[password hash] and body=[json object].
   *
   * <p>The JSON request object has the type { properties?: string[], functions?: { name: string,
   * args: unknown[] }[] }. Names of functions are in JS style (camelCase).
   *
   * <p>The response will have type { error: string } or { properties?: string[], functions?:
   * unknown[] }, with properties and functions each present if they were present on the request
   * object.
   *
   * <p>Enumerated types (Item, Familiar, Modifier, etc.) can be passed in with either of the
   * following placeholder formats: { objectType: EnumeratedTypeName, identifierString: string } | {
   * objectType: EnumeratedTypeName, identifierNumber: number }. If both identifierString and
   * identifierNumber are present, identifierNumber will be prioritized. Enumerated types will be
   * returned as a POJO with all the fields of a JS enumerated type, except any second-level
   * referenced objects will be left as placeholders. Objects will also have the placeholder fields
   * (objectType, identifierString, identifierNumber if applicable) set.
   *
   * <p>Details for an enumerated type instance can be requested with the special "identity"
   * function, which takes one placeholder argument.
   *
   * @param request Request body
   */
  private void handleJsonApi(String request) {
    this.contentType = "application/json";

    if (!KoLmafia.permitsContinue()) {
      this.statusLine = "HTTP/1.1 503 Service Unavailable";
      this.responseCode = 503;
      this.responseText = JSON.toJSONString(Map.of("error", "KoLmafia is in an error state."));
      return;
    }

    JSONObject json;
    try {
      json = JSON.parseObject(request);
    } catch (JSONException e) {
      jsonError("Invalid JSON object in request.");
      return;
    }

    var result = new JSONObject();

    var properties = json.get("properties");
    if (properties instanceof JSONArray propertiesArray) {
      var nonString = propertiesArray.stream().filter(name -> !(name instanceof String)).toList();
      if (!nonString.isEmpty()) {
        jsonError("Invalid property names " + JSON.toJSONString(nonString));
        return;
      }
      // Now all elements must be strings.
      result.put(
          "properties",
          propertiesArray.stream().map(name -> Preferences.getString((String) name)).toList());
    }

    var functions = json.get("functions");
    if (functions instanceof JSONArray functionsArray) {
      var nonMatching =
          functionsArray.stream()
              .filter(
                  func -> {
                    if (!(func instanceof JSONObject funcObject)) return true;
                    var name = funcObject.get("name");
                    var args = funcObject.get("args");
                    if (!(name instanceof String) || !(args instanceof JSONArray)) return true;
                    return ((JSONArray) args).contains(null);
                  })
              .toList();
      if (!nonMatching.isEmpty()) {
        jsonError("Invalid function calls " + JSON.toJSONString(nonMatching));
        return;
      }

      // Everything validated. Transform all function arguments.
      var runtime = new AshRuntime();
      var functionsResult = new JSONArray(functionsArray.size());
      for (var func : functionsArray) {
        var funcObject = (JSONObject) func;
        var name = (String) funcObject.get("name");
        var underscore = underscoreName(name);
        var args = (JSONArray) funcObject.get("args");

        try {
          if (name.equals("identity") && args.size() == 1) {
            try {
              functionsResult.add(
                  JSONValueConverter.asJSON(
                      JSONValueConverter.fromJSON(args.get(0), null).asProxy()));
            } catch (ValueConverter.ValueConverterException e) {
              jsonError("Invalid argument to identity: " + JSON.toJSONString(args.get(0)));
              return;
            }
            continue;
          }

          // This method throws if any of the values fail to convert, and returns null if no
          // matching method can be found. So if it returns and is non-null, all the arguments are
          // good to go.
          var functionWithArgs =
              new JSONValueConverter()
                  .findMatchingFunctionConvertArgs(
                      RuntimeLibrary.getFunctions(), underscore, args.toArray());

          if (functionWithArgs == null) {
            jsonError("Unable to find method " + name + " accepting arguments " + args + ".");
            return;
          }

          var function = functionWithArgs.function();
          var transformedArguments = functionWithArgs.ashArgs();

          var returnValue =
              function.execute(
                  runtime,
                  Stream.concat(Stream.of(runtime), transformedArguments.stream()).toArray());

          functionsResult.add(JSONValueConverter.asJSON(returnValue));
        } catch (ValueConverter.ValueConverterException e) {
          jsonError("Failed to convert arguments to ASH: " + e.getMessage());
          return;
        } catch (Exception e) {
          jsonError("Exception " + e.getClass().getName() + " on " + name + ": " + e.getMessage());
          return;
        }
      }

      result.put("functions", functionsResult);
    }

    this.statusLine = "HTTP/1.1 200 OK";
    this.responseCode = 200;
    try {
      this.responseText = JSON.toJSONString(result);
    } catch (JSONException e) {
      jsonError("Failed to serialize result: " + e.getMessage());
    }
  }

  private void submitCommand(String command) {
    submitCommand(command, false, true);
  }

  private void submitCommand(String command, boolean suppressUpdate) {
    submitCommand(command, suppressUpdate, true);
  }

  private void submitCommand(String command, boolean suppressUpdate, boolean waitForCompletion) {
    // Wait until any restoration scripts finish running before
    // submitting a command to the CLI

    this.waitForRecoveryToComplete();

    try {
      GenericRequest.suppressUpdate(suppressUpdate);
      CommandDisplayFrame.executeCommand(GenericRequest.decodeField(command));

      if (waitForCompletion) {
        this.waitForCommandCompletion();
      }
    } finally {
      GenericRequest.suppressUpdate(false);
    }
  }

  public void waitForCommandCompletion() {
    while (CommandDisplayFrame.hasQueuedCommands()) {
      this.pauser.pause(50);
    }
  }

  private static final Pattern JS_COMMAND =
      Pattern.compile("\"<font color=green>(.*?)\\.<!--js\\((.*?)\\)-->");

  private static String decorateJsCommands(String chatText) {
    var matcher = JS_COMMAND.matcher(chatText);

    if (!matcher.find()) return chatText;

    return makeLink(chatText, matcher);
  }

  private static String makeLink(String chatText, Matcher matcher) {
    return chatText.substring(0, matcher.start(1))
        + "<span style=\\\"cursor:pointer;\\\" onclick=\\\""
        + matcher.group(2)
        + ";\\\">"
        + matcher.group(1)
        + "</span>"
        + chatText.substring(matcher.end(1));
  }

  private void handleChat() {
    String path = this.getPath();
    boolean tabbedChat = path.contains("j=1");
    String chatText = "";

    if (path.startsWith("newchatmessages.php")) {
      chatText = tabbedChat ? getTabbedChatMessages() : getNontabbedChatMessages();
    } else if (path.startsWith("submitnewchat.php")) {
      if (ChatManager.getCurrentChannel() == null) {
        ChatSender.sendMessage(null, "/listen", true);
      }

      chatText =
          ChatSender.sendMessage(
              new LinkedList<>(), this.getFormField("graf"), true, false, tabbedChat);

      if (tabbedChat && chatText.startsWith("{")) {
        ChatPoller.handleNewChat(chatText, this.getFormField("graf"), ChatPoller.localLastSeen);
      }

      chatText = decorateJsCommands(chatText);
    }

    if (Preferences.getBoolean("relayFormatsChatText")) {
      chatText = ChatFormatter.formatExternalMessage(chatText);
    }

    this.pseudoResponse("HTTP/1.1 200 OK", chatText);
  }

  private static final Pattern LASTTIME_PATTERN = Pattern.compile("lasttime=(\\d+)");
  private static final Pattern AFK_PATTERN = Pattern.compile("afk=(\\d+)");

  private String getNontabbedChatMessages() {
    String URL = this.getPath();

    Matcher lastTimeMatcher = RelayRequest.LASTTIME_PATTERN.matcher(URL);
    long lastSeen =
        lastTimeMatcher.find() ? StringUtilities.parseLong(lastTimeMatcher.group(1)) : 0;

    Matcher afkMatcher = RelayRequest.AFK_PATTERN.matcher(URL);
    boolean paused = afkMatcher.find() && afkMatcher.group(1).equals("1");

    // If the browser's lchat is paused, we pause too.
    ChatPoller.pauseChat(paused, false);

    List<HistoryEntry> chatMessages;
    synchronized (lock) {
      ChatPoller.serverPolled();
      chatMessages = ChatPoller.getEntries(lastSeen, true, paused);
    }

    StringBuilder chatResponse = new StringBuilder();
    boolean needsLineBreak = false;

    for (HistoryEntry chatMessage : chatMessages) {
      String content = chatMessage.getContent();

      if (content != null && content.length() > 0) {
        if (needsLineBreak) {
          chatResponse.append("<br>");
        }

        needsLineBreak =
            !content.endsWith("<br>") && !content.endsWith("<br/>") && !content.endsWith("</br>");

        chatResponse.append(content);
      }

      lastSeen = Math.max(lastSeen, chatMessage.getServerLastSeen());
    }

    chatResponse.append("<!--lastseen:");
    chatResponse.append(KoLConstants.CHAT_LASTSEEN_FORMAT.format(lastSeen));
    chatResponse.append("-->");

    return chatResponse.toString();
  }

  private String getTabbedChatMessages() {
    if (ChatManager.getCurrentChannel() == null) {
      ChatSender.sendMessage(null, "/listen", true);
    }

    // mchat is also able to retrieve messages since a particular
    // timestamp, just like lchat.

    long lastSeen = StringUtilities.parseLong(this.getFormField("lasttime"));
    ChatRequest request = new ChatRequest(lastSeen, true, false);

    synchronized (lock) {
      ChatPoller.serverPolled();
      request.run();
    }

    String chatResponse = request.responseText == null ? "" : request.responseText;

    if (chatResponse.startsWith("{")) {
      // Get messages that the Chat Manager knows about that
      // are new since we last polled

      long localLastSeen = ChatPoller.localLastSeen;
      List<HistoryEntry> newEntries = ChatPoller.getOldEntries(true);
      ArrayList<ChatMessage> messages = new ArrayList<>();
      for (HistoryEntry entry : newEntries) {
        if (entry instanceof SentMessageEntry) {
          messages.addAll(entry.getChatMessages());
        }
      }

      ChatPoller.handleNewChat(chatResponse, "", localLastSeen);

      // If we have sent messages, prepend them to the msgs array in the response
      if (messages.size() > 0) {
        StringBuilder buffer = new StringBuilder(chatResponse);
        String string = "\"msgs\":[";
        int index = buffer.indexOf(string);
        if (index != -1) {
          index += string.length();
          for (ChatMessage message : messages) {
            com.alibaba.fastjson2.JSONObject object = message.toJSON();
            if (object != null) {
              string = object.toString();
              buffer.insert(index, string);
              index += string.length();
              if (buffer.charAt(index) != ']') {
                buffer.insert(index++, ',');
              }
            }
          }
          chatResponse = buffer.toString();
        }
      }
    }

    return chatResponse;
  }

  public void handleSimple() {
    // If there is an attempt to view the error page, or if
    // there is an attempt to view the robots file, neither
    // are available on KoL, so return.

    String path = this.getBasePath();

    if (path.equals("missingimage.gif") || path.endsWith("robots.txt")) {
      this.sendNotFound();
      return;
    }

    // If this is a command from the browser, handle it before
    // moving on to anything else.

    if (path.startsWith("KoLmafia/")) {
      this.handleCommand();
      return;
    }

    // Check to see if it's a request from the local images folder.
    // If it is, go ahead and send it.
    if (path.startsWith("images/") || path.startsWith("iii/") || path.endsWith("favicon.ico")) {
      if (path.startsWith("images/playerpics/")) {
        FileUtilities.downloadImage(
            "http://pics.communityofloathing.com/albums/"
                + path.substring(path.indexOf("playerpics")));
      }
      this.sendLocalImage(path);
      return;
    }

    // If it's an ASH/JS relay override script, handle it

    String relayField = this.getFormField("relay");
    if (path.endsWith(".ash")
        || (path.endsWith(".js") && relayField != null && relayField.equals("true"))) {
      if (!KoLmafiaASH.getClientHTML(this)) {
        this.sendNotFound();
      }

      return;
    }

    // Local files never have form fields.	Remove them, because
    // they're probably just used for data tracking purposes
    // client-side.

    this.data.clear();
    this.sendLocalFile(path);
  }

  @Override
  public void run() {
    String path = this.getBasePath();

    this.lastModified = 0L;

    if (path.startsWith("http")) {
      super.run();
      return;
    }

    if (!path.endsWith(".php")) {
      this.handleSimple();
      return;
    }

    // If it's a chat request, handle it right away and return.
    // Otherwise, continue on.

    if (this.isChatRequest) {
      this.handleChat();
      return;
    }

    // If it gets this far, consider firing a relay browser
    // override for it.

    if (this.allowOverride && KoLmafiaASH.getClientHTML(this)) {
      return;
    }

    if (this.isRootsetRequest) {
      super.run();

      String mainpane = this.getFormField("mainpane");

      if (mainpane != null) {
        if (!mainpane.contains(".php")) {
          mainpane = mainpane + ".php";
        }

        this.responseText =
            StringUtilities.singleStringReplace(this.responseText, "main.php", mainpane);
      }

      return;
    }

    if (path.startsWith("lchat.php")) {
      super.run();

      this.responseText = StringUtilities.globalStringReplace(this.responseText, "<p>", "<br><br>");
      this.responseText = StringUtilities.globalStringReplace(this.responseText, "<P>", "<br><br>");
      this.responseText = StringUtilities.singleStringDelete(this.responseText, "</span>");

      return;
    }

    if (path.startsWith("desc_item.php")) {
      String descId = this.getFormField("whichitem");

      // Show the Wiki, if that is desired
      if (descId != null && Preferences.getBoolean("relayAddsWikiLinks")) {
        int itemId = ItemDatabase.getItemIdFromDescription(descId);
        AdventureResult item = ItemPool.get(itemId, 0);
        String location = WikiUtilities.getWikiLocation(item);
        if (location != null) {
          this.pseudoResponse("HTTP/1.1 302 Found", location);
          return;
        }
      }
    }

    if (path.startsWith("desc_effect.php")) {
      String descId = this.getFormField("whicheffect");

      // Show the Wiki, if that is desired
      if (descId != null && Preferences.getBoolean("relayAddsWikiLinks")) {
        int effectId = EffectDatabase.getEffectIdFromDescription(descId);
        AdventureResult effect = EffectPool.get(effectId);
        String location = WikiUtilities.getWikiLocation(effect);
        if (location != null) {
          this.pseudoResponse("HTTP/1.1 302 Found", location);
          return;
        }
      }
    }

    if (path.startsWith("peevpee.php")) {
      String action = this.getFormField("action");
      if (action != null && action.equals("fight")) {
        RelayRequest.executeBeforePVPScript();
      }
    }

    String urlString = this.getURLString();
    KoLAdventure adventure = AdventureDatabase.getAdventureByURL(urlString);
    String adventureName =
        adventure != null
            ? adventure.getAdventureName()
            : AdventureDatabase.getUnknownName(urlString);
    String nextAdventure =
        adventureName != null
            ? adventureName
            : UseItemRequest.getAdventuresUsed(urlString) > 0 ? "None" : null;
    boolean wasAdventure =
        (nextAdventure != null && !urlString.equals("basement.php"))
            || urlString.startsWith("fight.php")
            || urlString.startsWith("choice.php");

    if (this.sendWarnings(adventure, adventureName, nextAdventure)) {
      return;
    }

    // If it gets this far, it's a normal file.  Go ahead and
    // process it accordingly.

    super.run();

    if (this.responseCode == 302) {
      this.pseudoResponse("HTTP/1.1 302 Found", this.redirectLocation);
    } else if (this.responseCode == 304) {
      this.pseudoResponse("HTTP/1.1 304 Not Modified", null);
    } else if (this.responseCode != 200) {
      this.sendNotFound();
    } else if (wasAdventure) {
      RelayRequest.executeAfterAdventureScript();
    }
  }

  /**
   * Centralized method for sending warnings before executing a relay request. Call individual
   * warnings from here.
   *
   * @param adventure
   * @param adventureName
   * @param nextAdventure
   * @return <b>true</b> if a pseudoresponse was displayed and the RelayRequest should stop before
   *     run()-ing.
   */
  private boolean sendWarnings(KoLAdventure adventure, String adventureName, String nextAdventure) {
    switch (KoLCharacter.getLimitMode()) {
      case NONE -> {}
      case SPELUNKY -> {
        // If we are playing Spelunky, a specialized set of warnings are relevant
        return this.sendSpelunkyWarning(adventure);
      }
      default -> {
        // If we are in some other limitmode, no warnings are relevant
        return false;
      }
    }

    String path = this.getBasePath();
    String urlString = this.getURLString();
    AreaCombatData areaSummary;
    boolean isNonCombatsOnly = false;

    // basement.php is a KoLAdventure, but with no additional form
    // fields, it simply shows you the current basement level.
    if (urlString.equals("basement.php")) {
      return false;
    }

    if (this.sendCounterWarning()) {
      return true;
    }

    // Do some checks fighting infernal seals
    // - make sure player is wielding a club

    if (this.sendInfernalSealWarning(urlString)) {
      return true;
    }

    // Do some checks for the battlefield:
    // - make sure player doesn't lose a Wossname by accident
    // - give player chance to cash in dimes and quarters

    if (this.sendBattlefieldWarning(urlString, adventure)) {
      return true;
    }

    // Perhaps warn player before freeing King Ralph

    if (this.sendBreakPrismWarning(urlString)) {
      return true;
    }

    if (adventure != null) {
      isNonCombatsOnly = adventure.isNonCombatsOnly();
    }

    if (nextAdventure != null
        && RecoveryManager.isRecoveryPossible()
        && this.getFormField(Confirm.RECOVERY) == null) {
      boolean isScript = !isNonCombatsOnly && Preferences.getBoolean("relayRunsBeforeBattleScript");
      boolean isMood = !isNonCombatsOnly && Preferences.getBoolean("relayMaintainsEffects");
      boolean isHealth = !isNonCombatsOnly && Preferences.getBoolean("relayMaintainsHealth");
      boolean isMana = !isNonCombatsOnly && Preferences.getBoolean("relayMaintainsMana");

      KoLmafia.forceContinue();
      KoLAdventure.setNextAdventure(adventure);
      RecoveryManager.runBetweenBattleChecks(isScript, isMood, isHealth, isMana);

      if (!KoLmafia.permitsContinue() && Preferences.getBoolean("relayWarnOnRecoverFailure")) {
        this.sendGeneralWarning(
            "beatenup.gif",
            "Between battle actions failed. Click the image if you'd like to continue anyway.",
            Confirm.RECOVERY);
        return true;
      }
    }

    if (nextAdventure != null || EquipmentRequest.isEquipmentChange(path)) {
      // Wait until any restoration scripts finish running
      // before allowing an adventuring request to continue.

      this.waitForRecoveryToComplete();
    }

    // Can opt out of relay warnings
    if (!Preferences.getBoolean("relayShowWarnings")) {
      return false;
    }

    if (((adventureName != null && !isNonCombatsOnly)
            || (path.startsWith("inv_use.php") && UseItemRequest.getAdventuresUsed(path) > 0))
        && (this.sendFamiliarWarning() || this.sendKungFuWarning())) {
      return true;
    }

    if (adventureName != null && KoLCharacter.isFallingDown()) {
      if (this.sendWineglassWarning(adventure)) {
        return true;
      }
      if (this.sendOverdrunkAdventureWarning(adventure)) {
        return true;
      }
    }

    if (this.sendColosseumWarning(adventure)) {
      return true;
    }

    if (this.sendGremlinWarning(adventure)) {
      return true;
    }

    if (this.sendBossWarning(path, adventure)) {
      return true;
    }

    if (this.sendDesertWeaponWarning()) {
      return true;
    }

    if (this.sendDesertOffhandWarning()) {
      return true;
    }

    if (this.sendUnhydratedDesertWarning()) {
      return true;
    }

    if (this.sendMacheteWarning()) {
      return true;
    }

    if (this.sendMohawkWigWarning()) {
      return true;
    }

    if (this.sendSpringShoesWarning()) {
      return true;
    }

    if (this.sendBoringDoorsWarning()) {
      return true;
    }

    if (this.sendPoolSkillWarning()) {
      return true;
    }

    if (this.sendCellarWarning()) {
      return true;
    }

    if (this.sendBoilerWarning()) {
      return true;
    }

    if (this.sendZeppelinWarning()) {
      return true;
    }

    if (this.sendDiaryWarning()) {
      return true;
    }

    if (this.sendSorceressWarning(urlString)) {
      return true;
    }

    if (this.sendHardcorePVPWarning(urlString)) {
      return true;
    }

    if (this.sendTransformWarning()) {
      return true;
    }

    return path.contains("whichplace=arcade") && this.sendArcadeWarning();
  }

  public boolean sendCounterWarning() {
    TurnCounter expired = TurnCounter.getExpiredCounter(this, true);
    while (expired != null) {
      // Read and discard expired informational counters
      expired = TurnCounter.getExpiredCounter(this, true);
    }

    StringBuilder msg = null;
    String image = null;
    boolean lights = false;
    boolean voteMonster = false;

    String urlString = this.getURLString();
    boolean isSkill = urlString.startsWith("runskillz.php");
    int skillId = isSkill ? UseSkillRequest.getSkillId(urlString) : 0;
    String skillName = isSkill ? SkillDatabase.getSkillName(skillId) : "";

    expired = TurnCounter.getExpiredCounter(this, false);
    while (expired != null) {
      int remain = expired.getTurnsRemaining();
      if (remain < 0) {
        expired = TurnCounter.getExpiredCounter(this, false);
        continue;
      }
      if (msg == null) {
        msg = new StringBuilder();
      } else {
        msg.append("<br>");
      }
      image = expired.getImage();
      switch (expired.getLabel()) {
        case "Spookyraven Lights Out" -> lights = true;
        case "Vote Monster" -> voteMonster = true;
      }
      msg.append("The ");
      msg.append(expired.getLabel());
      switch (remain) {
        case 0 -> {
          msg.append(" counter has expired");
          if (isSkill) {
            msg.append(". Since the ");
            msg.append(skillName);
            msg.append(" skill will use a turn, you may wish to delay using it at this time.");
          } else {
            msg.append(", so you may wish to adventure somewhere else at this time.");
          }
        }
        case 1 -> msg.append(
            " counter will expire after 1 more turn, so you may wish to adventure somewhere else that takes 1 turn.");
        default -> {
          msg.append(" counter will expire after ");
          msg.append(remain);
          msg.append(" more turns, so you may wish to adventure somewhere else for those turns.");
        }
      }
      expired = TurnCounter.getExpiredCounter(this, false);
    }

    if (msg != null) {
      msg.append("<br>If you are certain that ");
      if (isSkill) {
        msg.append("you'd like to use this skill at this time");
      } else {
        msg.append("this is where you'd like to adventure");
      }
      msg.append(", click on the image to proceed.");
      if (lights) {
        msg.append("<br><br>");
        msg.append(LightsOutManager.message(true));
      }
      if (voteMonster && !KoLCharacter.hasEquipped(ItemPool.I_VOTED_STICKER)) {
        msg.append(
            "You do not have the \"I voted\" sticker equipped, to continue without, click the icon on the left to adventure. ");
        msg.append("If you want the sticker equipped, click the icon on the right to adventure. ");
        this.sendOptionalWarning(
            Confirm.STICKER,
            msg.toString(),
            image,
            "ivoted.gif",
            "\"#\" onClick=\"singleUse('inv_equip.php','which=2&action=equip&slot=3&whichitem="
                + ItemPool.I_VOTED_STICKER
                + "&pwd="
                + GenericRequest.passwordHash
                + "&ajax=1');void(0);\"",
            null,
            null);
      } else {
        this.sendGeneralWarning(image, msg.toString(), Confirm.COUNTER, isSkill);
      }
      return true;
    }

    return false;
  }

  public static void executeBeforePVPScript() {
    if (Preferences.getBoolean("relayRunsBeforePVPScript")) {
      KoLmafia.executeBeforePVPScript();
    }
  }

  public static void executeAfterAdventureScript() {
    if (RecoveryManager.isRecoveryPossible()
        && Preferences.getBoolean("relayRunsAfterAdventureScript")) {
      KoLmafia.executeAfterAdventureScript();
    }
  }

  private void waitForRecoveryToComplete() {
    while (RecoveryManager.isRecoveryActive() || MoodManager.isExecuting()) {
      this.pauser.pause(100);
    }
  }

  private static boolean showWikiLink(final String item) {
    return Preferences.getBoolean("relayAddsWikiLinks");
  }
}
