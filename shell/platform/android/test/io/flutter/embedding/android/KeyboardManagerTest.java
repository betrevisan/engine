package io.flutter.embedding.android;

import static android.view.KeyEvent.*;
import static io.flutter.embedding.android.KeyData.Type;
import static io.flutter.util.KeyCodes.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.JSONMessageCodec;
import io.flutter.util.FakeKeyEvent;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import junit.framework.Assert;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.robolectric.annotation.Config;

@Config(manifest = Config.NONE)
@RunWith(AndroidJUnit4.class)
public class KeyboardManagerTest {
  public static final int SCAN_KEY_A = 0x1e;
  public static final int SCAN_DIGIT1 = 0x2;
  public static final int SCAN_SHIFT_LEFT = 0x2a;
  public static final int SCAN_SHIFT_RIGHT = 0x36;
  public static final int SCAN_CONTROL_LEFT = 0x1d;
  public static final int SCAN_CONTROL_RIGHT = 0x61;
  public static final int SCAN_ALT_LEFT = 0x38;
  public static final int SCAN_ALT_RIGHT = 0x64;
  public static final int SCAN_ARROW_LEFT = 0x69;
  public static final int SCAN_ARROW_RIGHT = 0x6a;
  public static final int SCAN_CAPS_LOCK = 0x3a;

  public static final boolean DOWN_EVENT = true;
  public static final boolean UP_EVENT = false;
  public static final boolean SHIFT_LEFT_EVENT = true;
  public static final boolean SHIFT_RIGHT_EVENT = false;

  private static final int DEAD_KEY = '`' | KeyCharacterMap.COMBINING_ACCENT;

  /**
   * Records a message that {@link KeyboardManager} sends to outside.
   *
   * <p>A call record can originate from many sources, indicated by its {@link type}. Different
   * types will have different fields filled, leaving others empty.
   */
  static class CallRecord {
    enum Kind {
      /**
       * The channel responder sent a message through the key event channel.
       *
       * <p>This call record will have a non-null {@link channelObject}, with an optional {@link
       * reply}.
       */
      kChannel,
      /**
       * The embedder responder sent a message through the key data channel.
       *
       * <p>This call record will have a non-null {@link keyData}, with an optional {@link reply}.
       */
      kEmbedder,
    }

    /**
     * Construct an empty call record.
     *
     * <p>Use the static functions to constuct specific types instead.
     */
    private CallRecord() {}

    Kind kind;

    /**
     * The callback given by the keyboard manager.
     *
     * <p>It might be null, which probably means it is a synthesized event and requires no reply.
     * Otherwise, invoke this callback with whether the event is handled for the keyboard manager to
     * continue processing the key event.
     */
    public Consumer<Boolean> reply;
    /** The data for a call record of kind {@link Kind.kChannel}. */
    public JSONObject channelObject;
    /** The data for a call record of kind {@link Kind.kEmbedder}. */
    public KeyData keyData;

    /** Construct a call record of kind {@link Kind.kChannel}. */
    static CallRecord channelCall(
        @NonNull JSONObject channelObject, @Nullable Consumer<Boolean> reply) {
      final CallRecord record = new CallRecord();
      record.kind = Kind.kChannel;
      record.channelObject = channelObject;
      record.reply = reply;
      return record;
    }

    /** Construct a call record of kind {@link Kind.kEmbedder}. */
    static CallRecord embedderCall(@NonNull KeyData keyData, @Nullable Consumer<Boolean> reply) {
      final CallRecord record = new CallRecord();
      record.kind = Kind.kEmbedder;
      record.keyData = keyData;
      record.reply = reply;
      return record;
    }
  }

  /**
   * Build a response to a channel message sent by the channel responder.
   *
   * @param handled whether the event is handled.
   */
  static ByteBuffer buildJsonResponse(boolean handled) {
    JSONObject body = new JSONObject();
    try {
      body.put("handled", handled);
    } catch (JSONException e) {
      assertNull(e);
    }
    ByteBuffer binaryReply = JSONMessageCodec.INSTANCE.encodeMessage(body);
    binaryReply.rewind();
    return binaryReply;
  }

  /**
   * Build a response to an embedder message sent by the embedder responder.
   *
   * @param handled whether the event is handled.
   */
  static ByteBuffer buildBinaryResponse(boolean handled) {
    byte[] body = new byte[1];
    body[0] = (byte) (handled ? 1 : 0);
    final ByteBuffer binaryReply = ByteBuffer.wrap(body);
    binaryReply.rewind();
    return binaryReply;
  }

  /**
   * Used to configure how to process a channel message.
   *
   * <p>When the channel responder sends a channel message, this functional interface will be
   * invoked. Its first argument will be the detailed data. The second argument will be a nullable
   * reply callback, which should be called to mock the reply from the framework.
   */
  @FunctionalInterface
  static interface ChannelCallHandler extends BiConsumer<JSONObject, Consumer<Boolean>> {}

  /**
   * Used to configure how to process an embedder message.
   *
   * <p>When the embedder responder sends a key data, this functional interface will be invoked. Its
   * first argument will be the detailed data. The second argument will be a nullable reply
   * callback, which should be called to mock the reply from the framework.
   */
  @FunctionalInterface
  static interface EmbedderCallHandler extends BiConsumer<KeyData, Consumer<Boolean>> {}

  static class KeyboardTester {
    public KeyboardTester() {
      respondToChannelCallsWith(false);
      respondToEmbedderCallsWith(false);
      respondToTextInputWith(false);

      BinaryMessenger mockMessenger = mock(BinaryMessenger.class);
      doAnswer(invocation -> onMessengerMessage(invocation))
          .when(mockMessenger)
          .send(any(String.class), any(ByteBuffer.class), eq(null));
      doAnswer(invocation -> onMessengerMessage(invocation))
          .when(mockMessenger)
          .send(any(String.class), any(ByteBuffer.class), any(BinaryMessenger.BinaryReply.class));

      mockView = mock(KeyboardManager.ViewDelegate.class);
      doAnswer(invocation -> mockMessenger).when(mockView).getBinaryMessenger();
      doAnswer(invocation -> textInputResult)
          .when(mockView)
          .onTextInputKeyEvent(any(KeyEvent.class));
      doAnswer(
              invocation -> {
                KeyEvent event = invocation.getArgument(0);
                boolean handled = keyboardManager.handleEvent(event);
                assertEquals(handled, false);
                return null;
              })
          .when(mockView)
          .redispatch(any(KeyEvent.class));

      keyboardManager = new KeyboardManager(mockView);
    }

    public @Mock KeyboardManager.ViewDelegate mockView;
    public KeyboardManager keyboardManager;

    /** Set channel calls to respond immediately with the given response. */
    public void respondToChannelCallsWith(boolean handled) {
      channelHandler =
          (JSONObject data, Consumer<Boolean> reply) -> {
            if (reply != null) {
              reply.accept(handled);
            }
          };
    }

    /**
     * Record channel calls to the given storage.
     *
     * <p>They are not responded to until the stored callbacks are manually called.
     */
    public void recordChannelCallsTo(@NonNull ArrayList<CallRecord> storage) {
      channelHandler =
          (JSONObject data, Consumer<Boolean> reply) -> {
            storage.add(CallRecord.channelCall(data, reply));
          };
    }

    /** Set embedder calls to respond immediately with the given response. */
    public void respondToEmbedderCallsWith(boolean handled) {
      embedderHandler =
          (KeyData keyData, Consumer<Boolean> reply) -> {
            if (reply != null) {
              reply.accept(handled);
            }
          };
    }

    /**
     * Record embedder calls to the given storage.
     *
     * <p>They are not responded to until the stored callbacks are manually called.
     */
    public void recordEmbedderCallsTo(@NonNull ArrayList<CallRecord> storage) {
      embedderHandler =
          (KeyData keyData, Consumer<Boolean> reply) ->
              storage.add(CallRecord.embedderCall(keyData, reply));
    }

    /** Set text calls to respond with the given response. */
    public void respondToTextInputWith(boolean response) {
      textInputResult = response;
    }

    private ChannelCallHandler channelHandler;
    private EmbedderCallHandler embedderHandler;
    private Boolean textInputResult;

    private Object onMessengerMessage(@NonNull InvocationOnMock invocation) {
      final String channel = invocation.getArgument(0);
      final ByteBuffer buffer = invocation.getArgument(1);
      buffer.rewind();

      final BinaryMessenger.BinaryReply reply = invocation.getArgument(2);
      if (channel == "flutter/keyevent") {
        // Parse a channel call.
        final JSONObject jsonObject = (JSONObject) JSONMessageCodec.INSTANCE.decodeMessage(buffer);
        final Consumer<Boolean> jsonReply =
            reply == null ? null : handled -> reply.reply(buildJsonResponse(handled));
        channelHandler.accept(jsonObject, jsonReply);
      } else if (channel == "flutter/keydata") {
        // Parse an embedder call.
        final KeyData keyData = new KeyData(buffer);
        final Consumer<Boolean> booleanReply =
            reply == null ? null : handled -> reply.reply(buildBinaryResponse(handled));
        embedderHandler.accept(keyData, booleanReply);
      } else {
        assertTrue(false);
      }
      return null;
    }
  }

  /**
   * Assert that the channel call is an event that matches the given data.
   *
   * <p>For now this function only validates key code, but not scancode or characters.
   *
   * @param data the target data to be tested.
   * @param type the type of the data, usually "keydown" or "keyup".
   * @param keyCode the key code.
   */
  static void assertChannelEventEquals(
      @NonNull JSONObject message, @NonNull String type, @NonNull Integer keyCode) {
    try {
      assertEquals(type, message.get("type"));
      assertEquals("android", message.get("keymap"));
      assertEquals(keyCode, message.get("keyCode"));
    } catch (JSONException e) {
      assertNull(e);
    }
  }

  /** Assert that the embedder call is an event that matches the given data. */
  static void assertEmbedderEventEquals(
      @NonNull KeyData data,
      Type type,
      long physicalKey,
      long logicalKey,
      String character,
      boolean synthesized) {
    assertEquals(type, data.type);
    assertEquals(physicalKey, data.physicalKey);
    assertEquals(logicalKey, data.logicalKey);
    assertEquals(character, data.character);
    assertEquals(synthesized, data.synthesized);
  }

  static void verifyEmbedderEvents(List<CallRecord> receivedCalls, KeyData[] expectedData) {
    assertEquals(receivedCalls.size(), expectedData.length);
    for (int idx = 0; idx < receivedCalls.size(); idx += 1) {
      final KeyData data = expectedData[idx];
      assertEmbedderEventEquals(
          receivedCalls.get(idx).keyData,
          data.type,
          data.physicalKey,
          data.logicalKey,
          data.character,
          data.synthesized);
    }
  }

  static KeyData buildKeyData(
      Type type,
      long physicalKey,
      long logicalKey,
      @Nullable String characters,
      boolean synthesized) {
    final KeyData result = new KeyData();
    result.physicalKey = physicalKey;
    result.logicalKey = logicalKey;
    result.timestamp = 0x0;
    result.type = type;
    result.character = characters;
    result.synthesized = synthesized;
    return result;
  }

  /**
   * Start a new tester, generate a ShiftRight event under the specified condition, and return the
   * output events for that event.
   *
   * @param preEventLeftPressed Whether ShiftLeft was recorded as pressed before the event.
   * @param preEventRightPressed Whether ShiftRight was recorded as pressed before the event.
   * @param rightEventIsDown Whether the dispatched event is a key down of key up of ShiftRight.
   * @param truePressed Whether Shift is pressed as shown in the metaState of the event.
   * @return
   */
  public static List<CallRecord> testShiftRightEvent(
      boolean preEventLeftPressed,
      boolean preEventRightPressed,
      boolean rightEventIsDown,
      boolean truePressed) {
    final ArrayList<CallRecord> calls = new ArrayList<>();
    // Even though the event is for ShiftRight, we still set SHIFT | SHIFT_LEFT here.
    // See the comment in synchronizePressingKey for the reason.
    final int SHIFT_LEFT_ON = META_SHIFT_LEFT_ON | META_SHIFT_ON;

    final KeyboardTester tester = new KeyboardTester();
    tester.respondToTextInputWith(true); // Suppress redispatching
    if (preEventLeftPressed) {
      tester.keyboardManager.handleEvent(
          new FakeKeyEvent(
              ACTION_DOWN, SCAN_SHIFT_LEFT, KEYCODE_SHIFT_LEFT, 0, '\0', SHIFT_LEFT_ON));
    }
    if (preEventRightPressed) {
      tester.keyboardManager.handleEvent(
          new FakeKeyEvent(
              ACTION_DOWN, SCAN_SHIFT_RIGHT, KEYCODE_SHIFT_RIGHT, 0, '\0', SHIFT_LEFT_ON));
    }
    tester.recordEmbedderCallsTo(calls);
    tester.keyboardManager.handleEvent(
        new FakeKeyEvent(
            rightEventIsDown ? ACTION_DOWN : ACTION_UP,
            SCAN_SHIFT_RIGHT,
            KEYCODE_SHIFT_RIGHT,
            0,
            '\0',
            truePressed ? SHIFT_LEFT_ON : 0));
    return calls.stream()
        .filter(data -> data.keyData.physicalKey != 0)
        .collect(Collectors.toList());
  }

  public static KeyData buildShiftKeyData(boolean isLeft, boolean isDown, boolean isSynthesized) {
    final KeyData data = new KeyData();
    data.type = isDown ? Type.kDown : Type.kUp;
    data.physicalKey = isLeft ? PHYSICAL_SHIFT_LEFT : PHYSICAL_SHIFT_RIGHT;
    data.logicalKey = isLeft ? LOGICAL_SHIFT_LEFT : LOGICAL_SHIFT_RIGHT;
    data.synthesized = isSynthesized;
    return data;
  }

  /**
   * Print each byte of the given buffer as a hex (such as "0a" for 0x0a), and return the
   * concatenated string.
   *
   * <p>Used to compare binary content in byte buffers.
   */
  static String printBufferBytes(@NonNull ByteBuffer buffer) {
    final String[] results = new String[buffer.capacity()];
    for (int byteIdx = 0; byteIdx < buffer.capacity(); byteIdx += 1) {
      results[byteIdx] = String.format("%02x", buffer.get(byteIdx));
    }
    return String.join("", results);
  }

  @Before
  public void setUp() {
    MockitoAnnotations.openMocks(this);
  }

  // Tests start

  @Test
  public void serializeAndDeserializeKeyData() {
    // Test data1: Non-empty character, synthesized.
    final KeyData data1 = new KeyData();
    data1.physicalKey = 0x0a;
    data1.logicalKey = 0x0b;
    data1.timestamp = 0x0c;
    data1.type = Type.kRepeat;
    data1.character = "A";
    data1.synthesized = true;

    final ByteBuffer data1Buffer = data1.toBytes();

    assertEquals(
        ""
            + "0100000000000000"
            + "0c00000000000000"
            + "0200000000000000"
            + "0a00000000000000"
            + "0b00000000000000"
            + "0100000000000000"
            + "41",
        printBufferBytes(data1Buffer));
    // `position` is considered as the message size.
    assertEquals(49, data1Buffer.position());

    data1Buffer.rewind();
    final KeyData data1Loaded = new KeyData(data1Buffer);
    assertEquals(data1Loaded.timestamp, data1.timestamp);

    // Test data2: Empty character, not synthesized.
    final KeyData data2 = new KeyData();
    data2.physicalKey = 0xaaaabbbbccccl;
    data2.logicalKey = 0x666677778888l;
    data2.timestamp = 0x333344445555l;
    data2.type = Type.kUp;
    data2.character = null;
    data2.synthesized = false;

    final ByteBuffer data2Buffer = data2.toBytes();

    assertEquals(
        ""
            + "0000000000000000"
            + "5555444433330000"
            + "0100000000000000"
            + "ccccbbbbaaaa0000"
            + "8888777766660000"
            + "0000000000000000",
        printBufferBytes(data2Buffer));

    data2Buffer.rewind();
    final KeyData data2Loaded = new KeyData(data2Buffer);
    assertEquals(data2Loaded.timestamp, data2.timestamp);
  }

  @Test
  public void basicCombingCharactersTest() {
    final KeyboardManager.CharacterCombiner combiner = new KeyboardManager.CharacterCombiner();
    Assert.assertEquals(0, (int) combiner.applyCombiningCharacterToBaseCharacter(0));
    Assert.assertEquals('A', (int) combiner.applyCombiningCharacterToBaseCharacter('A'));
    Assert.assertEquals('B', (int) combiner.applyCombiningCharacterToBaseCharacter('B'));
    Assert.assertEquals('B', (int) combiner.applyCombiningCharacterToBaseCharacter('B'));
    Assert.assertEquals(0, (int) combiner.applyCombiningCharacterToBaseCharacter(0));
    Assert.assertEquals(0, (int) combiner.applyCombiningCharacterToBaseCharacter(0));

    Assert.assertEquals('`', (int) combiner.applyCombiningCharacterToBaseCharacter(DEAD_KEY));
    Assert.assertEquals('`', (int) combiner.applyCombiningCharacterToBaseCharacter(DEAD_KEY));
    Assert.assertEquals('À', (int) combiner.applyCombiningCharacterToBaseCharacter('A'));

    Assert.assertEquals('`', (int) combiner.applyCombiningCharacterToBaseCharacter(DEAD_KEY));
    Assert.assertEquals(0, (int) combiner.applyCombiningCharacterToBaseCharacter(0));
    // The 0 input should remove the combining state.
    Assert.assertEquals('A', (int) combiner.applyCombiningCharacterToBaseCharacter('A'));

    Assert.assertEquals(0, (int) combiner.applyCombiningCharacterToBaseCharacter(0));
    Assert.assertEquals('`', (int) combiner.applyCombiningCharacterToBaseCharacter(DEAD_KEY));
    Assert.assertEquals('À', (int) combiner.applyCombiningCharacterToBaseCharacter('A'));
  }

  @Test
  public void respondsTrueWhenHandlingNewEvents() {
    final KeyboardTester tester = new KeyboardTester();
    final KeyEvent keyEvent = new FakeKeyEvent(ACTION_DOWN, 65);
    final ArrayList<CallRecord> calls = new ArrayList<>();

    tester.recordChannelCallsTo(calls);

    final boolean result = tester.keyboardManager.handleEvent(keyEvent);

    assertEquals(true, result);
    assertEquals(calls.size(), 1);
    assertChannelEventEquals(calls.get(0).channelObject, "keydown", 65);

    // Don't send the key event to the text plugin if the only primary responder
    // hasn't responded.
    verify(tester.mockView, times(0)).onTextInputKeyEvent(any(KeyEvent.class));
    verify(tester.mockView, times(0)).redispatch(any(KeyEvent.class));
  }

  @Test
  public void channelReponderHandlesEvents() {
    final KeyboardTester tester = new KeyboardTester();
    final KeyEvent keyEvent = new FakeKeyEvent(ACTION_DOWN, 65);
    final ArrayList<CallRecord> calls = new ArrayList<>();

    tester.recordChannelCallsTo(calls);

    final boolean result = tester.keyboardManager.handleEvent(keyEvent);

    assertEquals(true, result);
    assertEquals(calls.size(), 1);
    assertChannelEventEquals(calls.get(0).channelObject, "keydown", 65);

    // Don't send the key event to the text plugin if the only primary responder
    // hasn't responded.
    verify(tester.mockView, times(0)).onTextInputKeyEvent(any(KeyEvent.class));
    verify(tester.mockView, times(0)).redispatch(any(KeyEvent.class));

    // If a primary responder handles the key event the propagation stops.
    assertNotNull(calls.get(0).reply);
    calls.get(0).reply.accept(true);
    verify(tester.mockView, times(0)).onTextInputKeyEvent(any(KeyEvent.class));
    verify(tester.mockView, times(0)).redispatch(any(KeyEvent.class));
  }

  @Test
  public void embedderReponderHandlesEvents() {
    final KeyboardTester tester = new KeyboardTester();
    final KeyEvent keyEvent = new FakeKeyEvent(ACTION_DOWN, SCAN_KEY_A, KEYCODE_A, 0, 'a', 0);
    final ArrayList<CallRecord> calls = new ArrayList<>();

    tester.recordEmbedderCallsTo(calls);

    final boolean result = tester.keyboardManager.handleEvent(keyEvent);

    assertEquals(true, result);
    assertEquals(calls.size(), 1);
    assertEmbedderEventEquals(
        calls.get(0).keyData, Type.kDown, PHYSICAL_KEY_A, LOGICAL_KEY_A, "a", false);

    // Don't send the key event to the text plugin if the only primary responder
    // hasn't responded.
    verify(tester.mockView, times(0)).onTextInputKeyEvent(any(KeyEvent.class));
    verify(tester.mockView, times(0)).redispatch(any(KeyEvent.class));

    // If a primary responder handles the key event the propagation stops.
    assertNotNull(calls.get(0).reply);
    calls.get(0).reply.accept(true);
    verify(tester.mockView, times(0)).onTextInputKeyEvent(any(KeyEvent.class));
    verify(tester.mockView, times(0)).redispatch(any(KeyEvent.class));
  }

  @Test
  public void bothRespondersHandlesEvents() {
    final KeyboardTester tester = new KeyboardTester();
    final ArrayList<CallRecord> calls = new ArrayList<>();

    tester.recordChannelCallsTo(calls);
    tester.recordEmbedderCallsTo(calls);
    tester.respondToTextInputWith(true);

    final boolean result =
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(ACTION_DOWN, SCAN_KEY_A, KEYCODE_A, 0, 'a', 0));

    assertEquals(true, result);
    assertEquals(calls.size(), 2);
    assertEmbedderEventEquals(
        calls.get(0).keyData, Type.kDown, PHYSICAL_KEY_A, LOGICAL_KEY_A, "a", false);
    assertChannelEventEquals(calls.get(1).channelObject, "keydown", KEYCODE_A);

    verify(tester.mockView, times(0)).onTextInputKeyEvent(any(KeyEvent.class));
    verify(tester.mockView, times(0)).redispatch(any(KeyEvent.class));

    calls.get(0).reply.accept(true);
    verify(tester.mockView, times(0)).onTextInputKeyEvent(any(KeyEvent.class));
    verify(tester.mockView, times(0)).redispatch(any(KeyEvent.class));

    calls.get(1).reply.accept(true);
    verify(tester.mockView, times(0)).onTextInputKeyEvent(any(KeyEvent.class));
    verify(tester.mockView, times(0)).redispatch(any(KeyEvent.class));
  }

  @Test
  public void textInputHandlesEventsIfNoRespondersDo() {
    final KeyboardTester tester = new KeyboardTester();
    final KeyEvent keyEvent = new FakeKeyEvent(ACTION_DOWN, 65);
    final ArrayList<CallRecord> calls = new ArrayList<>();

    tester.recordChannelCallsTo(calls);

    final boolean result = tester.keyboardManager.handleEvent(keyEvent);

    assertEquals(true, result);
    assertEquals(calls.size(), 1);
    assertChannelEventEquals(calls.get(0).channelObject, "keydown", 65);

    // Don't send the key event to the text plugin if the only primary responder
    // hasn't responded.
    verify(tester.mockView, times(0)).onTextInputKeyEvent(any(KeyEvent.class));
    verify(tester.mockView, times(0)).redispatch(any(KeyEvent.class));

    // If no primary responder handles the key event the propagates to the text
    // input plugin.
    assertNotNull(calls.get(0).reply);
    // Let text input plugin handle the key event.
    tester.respondToTextInputWith(true);
    calls.get(0).reply.accept(false);

    verify(tester.mockView, times(1)).onTextInputKeyEvent(keyEvent);
    verify(tester.mockView, times(0)).redispatch(any(KeyEvent.class));
  }

  @Test
  public void redispatchEventsIfTextInputDoesntHandle() {
    final KeyboardTester tester = new KeyboardTester();
    final KeyEvent keyEvent = new FakeKeyEvent(ACTION_DOWN, 65);
    final ArrayList<CallRecord> calls = new ArrayList<>();

    tester.recordChannelCallsTo(calls);

    final boolean result = tester.keyboardManager.handleEvent(keyEvent);

    assertEquals(true, result);
    assertEquals(calls.size(), 1);
    assertChannelEventEquals(calls.get(0).channelObject, "keydown", 65);

    // Don't send the key event to the text plugin if the only primary responder
    // hasn't responded.
    verify(tester.mockView, times(0)).onTextInputKeyEvent(any(KeyEvent.class));
    verify(tester.mockView, times(0)).redispatch(any(KeyEvent.class));

    // Neither the primary responders nor text input plugin handles the event.
    tester.respondToTextInputWith(false);
    calls.get(0).reply.accept(false);

    verify(tester.mockView, times(1)).onTextInputKeyEvent(keyEvent);
    verify(tester.mockView, times(1)).redispatch(keyEvent);
  }

  @Test
  public void redispatchedEventsAreCorrectlySkipped() {
    final KeyboardTester tester = new KeyboardTester();
    final ArrayList<CallRecord> calls = new ArrayList<>();

    tester.recordChannelCallsTo(calls);

    final KeyEvent keyEvent = new FakeKeyEvent(ACTION_DOWN, 65);
    final boolean result = tester.keyboardManager.handleEvent(keyEvent);

    assertEquals(true, result);
    assertEquals(calls.size(), 1);
    assertChannelEventEquals(calls.get(0).channelObject, "keydown", 65);

    // Don't send the key event to the text plugin if the only primary responder
    // hasn't responded.
    verify(tester.mockView, times(0)).onTextInputKeyEvent(any(KeyEvent.class));
    verify(tester.mockView, times(0)).redispatch(any(KeyEvent.class));

    // Neither the primary responders nor text input plugin handles the event.
    tester.respondToTextInputWith(false);
    calls.get(0).reply.accept(false);

    verify(tester.mockView, times(1)).onTextInputKeyEvent(keyEvent);
    verify(tester.mockView, times(1)).redispatch(keyEvent);

    // It's redispatched to the keyboard manager, but no eventual key calls.
    assertEquals(calls.size(), 1);
  }

  @Test
  public void tapLowerA() {
    final KeyboardTester tester = new KeyboardTester();
    final ArrayList<CallRecord> calls = new ArrayList<>();

    tester.recordEmbedderCallsTo(calls);
    tester.respondToTextInputWith(true); // Suppress redispatching

    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(ACTION_DOWN, SCAN_KEY_A, KEYCODE_A, 0, 'a', 0)));

    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kDown, PHYSICAL_KEY_A, LOGICAL_KEY_A, "a", false),
        });
    calls.clear();

    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(ACTION_DOWN, SCAN_KEY_A, KEYCODE_A, 1, 'a', 0)));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kRepeat, PHYSICAL_KEY_A, LOGICAL_KEY_A, "a", false),
        });
    calls.clear();

    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(ACTION_UP, SCAN_KEY_A, KEYCODE_A, 0, 'a', 0)));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kUp, PHYSICAL_KEY_A, LOGICAL_KEY_A, null, false),
        });
    calls.clear();
  }

  @Test
  public void tapUpperA() {
    final KeyboardTester tester = new KeyboardTester();
    final ArrayList<CallRecord> calls = new ArrayList<>();

    tester.recordEmbedderCallsTo(calls);
    tester.respondToTextInputWith(true); // Suppress redispatching

    // ShiftLeft
    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(ACTION_DOWN, SCAN_SHIFT_LEFT, KEYCODE_SHIFT_LEFT, 0, '\0', 0x41)));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kDown, PHYSICAL_SHIFT_LEFT, LOGICAL_SHIFT_LEFT, null, false),
        });
    calls.clear();

    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(ACTION_DOWN, SCAN_KEY_A, KEYCODE_A, 0, 'A', 0x41)));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kDown, PHYSICAL_KEY_A, LOGICAL_KEY_A, "A", false),
        });
    calls.clear();

    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(ACTION_UP, SCAN_KEY_A, KEYCODE_A, 0, 'A', 0x41)));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kUp, PHYSICAL_KEY_A, LOGICAL_KEY_A, null, false),
        });
    calls.clear();

    // ShiftLeft
    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(ACTION_UP, SCAN_SHIFT_LEFT, KEYCODE_SHIFT_LEFT, 0, '\0', 0)));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kUp, PHYSICAL_SHIFT_LEFT, LOGICAL_SHIFT_LEFT, null, false),
        });
    calls.clear();
  }

  @Test
  public void eventsWithForgedCodes() {
    final KeyboardTester tester = new KeyboardTester();
    final ArrayList<CallRecord> calls = new ArrayList<>();

    tester.recordEmbedderCallsTo(calls);
    tester.respondToTextInputWith(true); // Suppress redispatching

    // Zero scan code.
    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(ACTION_DOWN, 0, KEYCODE_ENTER, 0, '\n', 0)));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kDown, 0x300000042L, LOGICAL_ENTER, "\n", false),
        });
    calls.clear();

    // Zero scan code and zero key code.
    assertEquals(
        true, tester.keyboardManager.handleEvent(new FakeKeyEvent(ACTION_DOWN, 0, 0, 0, '\0', 0)));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kDown, 0, 0, null, true),
        });
    calls.clear();

    // Unrecognized scan code. (Fictional test)
    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(ACTION_DOWN, 0xDEADBEEF, 0, 0, '\0', 0)));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kDown, 0x11DEADBEEFL, 0x1100000000L, null, false),
        });
    calls.clear();

    // Zero key code. (Fictional test; I have yet to find a real case.)
    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(ACTION_DOWN, SCAN_ARROW_LEFT, 0, 0, '\0', 0)));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kDown, PHYSICAL_ARROW_LEFT, 0x1100000000L, null, false),
        });
    calls.clear();

    // Unrecognized key code. (Fictional test)
    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(ACTION_DOWN, SCAN_ARROW_RIGHT, 0xDEADBEEF, 0, '\0', 0)));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kDown, PHYSICAL_ARROW_RIGHT, 0x11DEADBEEFL, null, false),
        });
    calls.clear();
  }

  @Test
  public void duplicateDownEventsArePrecededBySynthesizedUpEvents() {
    final KeyboardTester tester = new KeyboardTester();
    final ArrayList<CallRecord> calls = new ArrayList<>();

    tester.recordEmbedderCallsTo(calls);
    tester.respondToTextInputWith(true); // Suppress redispatching

    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(ACTION_DOWN, SCAN_KEY_A, KEYCODE_A, 0, 'a', 0)));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kDown, PHYSICAL_KEY_A, LOGICAL_KEY_A, "a", false),
        });
    calls.clear();

    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(ACTION_DOWN, SCAN_KEY_A, KEYCODE_A, 0, 'a', 0)));
    assertEquals(calls.size(), 2);
    assertEmbedderEventEquals(
        calls.get(0).keyData, Type.kUp, PHYSICAL_KEY_A, LOGICAL_KEY_A, null, true);
    assertEmbedderEventEquals(
        calls.get(1).keyData, Type.kDown, PHYSICAL_KEY_A, LOGICAL_KEY_A, "a", false);
    calls.clear();
  }

  @Test
  public void abruptUpEventsAreIgnored() {
    final KeyboardTester tester = new KeyboardTester();
    final ArrayList<CallRecord> calls = new ArrayList<>();

    tester.recordEmbedderCallsTo(calls);
    tester.respondToTextInputWith(true); // Suppress redispatching

    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(ACTION_UP, SCAN_KEY_A, KEYCODE_A, 0, 'a', 0)));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kDown, 0l, 0l, null, true),
        });
    calls.clear();
  }

  @Test
  public void modifierKeys() {
    final KeyboardTester tester = new KeyboardTester();
    final ArrayList<CallRecord> calls = new ArrayList<>();

    tester.recordEmbedderCallsTo(calls);
    tester.respondToTextInputWith(true); // Suppress redispatching

    // ShiftLeft
    tester.keyboardManager.handleEvent(
        new FakeKeyEvent(ACTION_DOWN, SCAN_SHIFT_LEFT, KEYCODE_SHIFT_LEFT, 0, '\0', 0x41));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kDown, PHYSICAL_SHIFT_LEFT, LOGICAL_SHIFT_LEFT, null, false),
        });
    calls.clear();

    tester.keyboardManager.handleEvent(
        new FakeKeyEvent(ACTION_UP, SCAN_SHIFT_LEFT, KEYCODE_SHIFT_LEFT, 0, '\0', 0));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kUp, PHYSICAL_SHIFT_LEFT, LOGICAL_SHIFT_LEFT, null, false),
        });
    calls.clear();

    // ShiftRight
    tester.keyboardManager.handleEvent(
        new FakeKeyEvent(ACTION_DOWN, SCAN_SHIFT_RIGHT, KEYCODE_SHIFT_RIGHT, 0, '\0', 0x41));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kDown, PHYSICAL_SHIFT_RIGHT, LOGICAL_SHIFT_RIGHT, null, false),
        });
    calls.clear();

    tester.keyboardManager.handleEvent(
        new FakeKeyEvent(ACTION_UP, SCAN_SHIFT_RIGHT, KEYCODE_SHIFT_RIGHT, 0, '\0', 0));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kUp, PHYSICAL_SHIFT_RIGHT, LOGICAL_SHIFT_RIGHT, null, false),
        });
    calls.clear();

    // ControlLeft
    tester.keyboardManager.handleEvent(
        new FakeKeyEvent(ACTION_DOWN, SCAN_CONTROL_LEFT, KEYCODE_CTRL_LEFT, 0, '\0', 0x3000));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kDown, PHYSICAL_CONTROL_LEFT, LOGICAL_CONTROL_LEFT, null, false),
        });
    calls.clear();

    tester.keyboardManager.handleEvent(
        new FakeKeyEvent(ACTION_UP, SCAN_CONTROL_LEFT, KEYCODE_CTRL_LEFT, 0, '\0', 0));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kUp, PHYSICAL_CONTROL_LEFT, LOGICAL_CONTROL_LEFT, null, false),
        });
    calls.clear();

    // ControlRight
    tester.keyboardManager.handleEvent(
        new FakeKeyEvent(ACTION_DOWN, SCAN_CONTROL_RIGHT, KEYCODE_CTRL_RIGHT, 0, '\0', 0x3000));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kDown, PHYSICAL_CONTROL_RIGHT, LOGICAL_CONTROL_RIGHT, null, false),
        });
    calls.clear();

    tester.keyboardManager.handleEvent(
        new FakeKeyEvent(ACTION_UP, SCAN_CONTROL_RIGHT, KEYCODE_CTRL_RIGHT, 0, '\0', 0));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kUp, PHYSICAL_CONTROL_RIGHT, LOGICAL_CONTROL_RIGHT, null, false),
        });
    calls.clear();

    // AltLeft
    tester.keyboardManager.handleEvent(
        new FakeKeyEvent(ACTION_DOWN, SCAN_ALT_LEFT, KEYCODE_ALT_LEFT, 0, '\0', 0x12));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kDown, PHYSICAL_ALT_LEFT, LOGICAL_ALT_LEFT, null, false),
        });
    calls.clear();

    tester.keyboardManager.handleEvent(
        new FakeKeyEvent(ACTION_UP, SCAN_ALT_LEFT, KEYCODE_ALT_LEFT, 0, '\0', 0));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kUp, PHYSICAL_ALT_LEFT, LOGICAL_ALT_LEFT, null, false),
        });
    calls.clear();

    // AltRight
    tester.keyboardManager.handleEvent(
        new FakeKeyEvent(ACTION_DOWN, SCAN_ALT_RIGHT, KEYCODE_ALT_RIGHT, 0, '\0', 0x12));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kDown, PHYSICAL_ALT_RIGHT, LOGICAL_ALT_RIGHT, null, false),
        });
    calls.clear();

    tester.keyboardManager.handleEvent(
        new FakeKeyEvent(ACTION_UP, SCAN_ALT_RIGHT, KEYCODE_ALT_RIGHT, 0, '\0', 0));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kUp, PHYSICAL_ALT_RIGHT, LOGICAL_ALT_RIGHT, null, false),
        });
    calls.clear();
  }

  @Test
  public void nonUsKeys() {
    final KeyboardTester tester = new KeyboardTester();
    final ArrayList<CallRecord> calls = new ArrayList<>();

    tester.recordEmbedderCallsTo(calls);
    tester.respondToTextInputWith(true); // Suppress redispatching

    // French 1
    tester.keyboardManager.handleEvent(
        new FakeKeyEvent(ACTION_DOWN, SCAN_DIGIT1, KEYCODE_1, 0, '1', 0));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kDown, PHYSICAL_DIGIT1, LOGICAL_DIGIT1, "1", false),
        });
    calls.clear();

    tester.keyboardManager.handleEvent(
        new FakeKeyEvent(ACTION_UP, SCAN_DIGIT1, KEYCODE_1, 0, '1', 0));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kUp, PHYSICAL_DIGIT1, LOGICAL_DIGIT1, null, false),
        });
    calls.clear();

    // French Shift-1
    tester.keyboardManager.handleEvent(
        new FakeKeyEvent(ACTION_DOWN, SCAN_SHIFT_LEFT, KEYCODE_SHIFT_LEFT, 0, '\0', 0x41));
    calls.clear();

    tester.keyboardManager.handleEvent(
        new FakeKeyEvent(ACTION_DOWN, SCAN_DIGIT1, KEYCODE_1, 0, '&', 0x41));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kDown, PHYSICAL_DIGIT1, LOGICAL_DIGIT1, "&", false),
        });
    calls.clear();

    tester.keyboardManager.handleEvent(
        new FakeKeyEvent(ACTION_UP, SCAN_DIGIT1, KEYCODE_1, 0, '&', 0x41));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kUp, PHYSICAL_DIGIT1, LOGICAL_DIGIT1, null, false),
        });
    calls.clear();

    tester.keyboardManager.handleEvent(
        new FakeKeyEvent(ACTION_UP, SCAN_SHIFT_LEFT, KEYCODE_SHIFT_LEFT, 0, '\0', 0));
    calls.clear();

    // Russian lowerA
    tester.keyboardManager.handleEvent(
        new FakeKeyEvent(ACTION_DOWN, SCAN_KEY_A, KEYCODE_A, 0, '\u0444', 0));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kDown, PHYSICAL_KEY_A, LOGICAL_KEY_A, "ф", false),
        });
    calls.clear();

    tester.keyboardManager.handleEvent(
        new FakeKeyEvent(ACTION_UP, SCAN_KEY_A, KEYCODE_A, 0, '\u0444', 0));
    verifyEmbedderEvents(
        calls,
        new KeyData[] {
          buildKeyData(Type.kUp, PHYSICAL_KEY_A, LOGICAL_KEY_A, null, false),
        });
    calls.clear();
  }

  @Test
  public void synchronizeShiftLeftDuringForeignKeyEvents() {
    // Test if ShiftLeft can be synchronized during events of ArrowLeft.
    final KeyboardTester tester = new KeyboardTester();
    final ArrayList<CallRecord> calls = new ArrayList<>();

    tester.recordEmbedderCallsTo(calls);
    tester.respondToTextInputWith(true); // Suppress redispatching

    final int SHIFT_LEFT_ON = META_SHIFT_LEFT_ON | META_SHIFT_ON;

    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(
                ACTION_DOWN, SCAN_ARROW_LEFT, KEYCODE_DPAD_LEFT, 0, '\0', SHIFT_LEFT_ON)));
    assertEquals(calls.size(), 2);
    assertEmbedderEventEquals(
        calls.get(0).keyData, Type.kDown, PHYSICAL_SHIFT_LEFT, LOGICAL_SHIFT_LEFT, null, true);
    calls.clear();

    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(ACTION_UP, SCAN_ARROW_LEFT, KEYCODE_DPAD_LEFT, 0, '\0', 0)));
    assertEquals(calls.size(), 2);
    assertEmbedderEventEquals(
        calls.get(0).keyData, Type.kUp, PHYSICAL_SHIFT_LEFT, LOGICAL_SHIFT_LEFT, null, true);
    calls.clear();
  }

  @Test
  public void synchronizeShiftLeftDuringSelfKeyEvents() {
    // Test if ShiftLeft can be synchronized during events of ShiftLeft.
    final KeyboardTester tester = new KeyboardTester();
    final ArrayList<CallRecord> calls = new ArrayList<>();

    tester.recordEmbedderCallsTo(calls);
    tester.respondToTextInputWith(true); // Suppress redispatching

    final int SHIFT_LEFT_ON = META_SHIFT_LEFT_ON | META_SHIFT_ON;
    // All 6 cases (3 types x 2 states) are arranged in the following order so that the starting
    // states for each case are the desired states.

    // Repeat event when current state is 0.
    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(
                ACTION_DOWN, SCAN_SHIFT_LEFT, KEYCODE_SHIFT_LEFT, 1, '\0', SHIFT_LEFT_ON)));
    assertEquals(calls.size(), 1);
    assertEmbedderEventEquals(
        calls.get(0).keyData, Type.kDown, PHYSICAL_SHIFT_LEFT, LOGICAL_SHIFT_LEFT, null, false);
    calls.clear();

    // Down event when the current state is 1.
    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(
                ACTION_DOWN, SCAN_SHIFT_LEFT, KEYCODE_SHIFT_LEFT, 0, '\0', SHIFT_LEFT_ON)));
    assertEquals(calls.size(), 2);
    assertEmbedderEventEquals(
        calls.get(0).keyData, Type.kUp, PHYSICAL_SHIFT_LEFT, LOGICAL_SHIFT_LEFT, null, true);
    assertEmbedderEventEquals(
        calls.get(1).keyData, Type.kDown, PHYSICAL_SHIFT_LEFT, LOGICAL_SHIFT_LEFT, null, false);
    calls.clear();

    // Up event when the current state is 1.
    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(ACTION_UP, SCAN_SHIFT_LEFT, KEYCODE_SHIFT_LEFT, 0, '\0', 0)));
    assertEquals(calls.size(), 1);
    assertEmbedderEventEquals(
        calls.get(0).keyData, Type.kUp, PHYSICAL_SHIFT_LEFT, LOGICAL_SHIFT_LEFT, null, false);
    calls.clear();

    // Up event when the current state is 0.
    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(ACTION_UP, SCAN_SHIFT_LEFT, KEYCODE_SHIFT_LEFT, 0, '\0', 0)));
    assertEquals(calls.size(), 1);
    assertEmbedderEventEquals(calls.get(0).keyData, Type.kDown, 0l, 0l, null, true);
    calls.clear();

    // Down event when the current state is 0.
    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(
                ACTION_DOWN, SCAN_SHIFT_LEFT, KEYCODE_SHIFT_LEFT, 0, '\0', SHIFT_LEFT_ON)));
    assertEquals(calls.size(), 1);
    assertEmbedderEventEquals(
        calls.get(0).keyData, Type.kDown, PHYSICAL_SHIFT_LEFT, LOGICAL_SHIFT_LEFT, null, false);
    calls.clear();

    // Repeat event when the current state is 1.
    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(
                ACTION_DOWN, SCAN_SHIFT_LEFT, KEYCODE_SHIFT_LEFT, 1, '\0', SHIFT_LEFT_ON)));
    assertEquals(calls.size(), 1);
    assertEmbedderEventEquals(
        calls.get(0).keyData, Type.kRepeat, PHYSICAL_SHIFT_LEFT, LOGICAL_SHIFT_LEFT, null, false);
    calls.clear();
  }

  @Test
  public void synchronizeShiftLeftDuringSiblingKeyEvents() {
    // Test if ShiftLeft can be synchronized during events of ShiftRight. The following events seem
    // to have weird metaStates that don't follow Android's documentation (always using left masks)
    // but are indeed observed on ChromeOS.

    // UP_EVENT, truePressed: false

    verifyEmbedderEvents(testShiftRightEvent(false, false, UP_EVENT, false), new KeyData[] {});
    verifyEmbedderEvents(
        testShiftRightEvent(false, true, UP_EVENT, false),
        new KeyData[] {
          buildShiftKeyData(SHIFT_RIGHT_EVENT, UP_EVENT, false),
        });
    verifyEmbedderEvents(
        testShiftRightEvent(true, false, UP_EVENT, false),
        new KeyData[] {
          buildShiftKeyData(SHIFT_LEFT_EVENT, UP_EVENT, true),
        });
    verifyEmbedderEvents(
        testShiftRightEvent(true, true, UP_EVENT, false),
        new KeyData[] {
          buildShiftKeyData(SHIFT_LEFT_EVENT, UP_EVENT, true),
          buildShiftKeyData(SHIFT_RIGHT_EVENT, UP_EVENT, false),
        });

    // UP_EVENT, truePressed: true

    verifyEmbedderEvents(
        testShiftRightEvent(false, false, UP_EVENT, true),
        new KeyData[] {
          buildShiftKeyData(SHIFT_LEFT_EVENT, DOWN_EVENT, true),
        });
    verifyEmbedderEvents(
        testShiftRightEvent(false, true, UP_EVENT, true),
        new KeyData[] {
          buildShiftKeyData(SHIFT_LEFT_EVENT, DOWN_EVENT, true),
          buildShiftKeyData(SHIFT_RIGHT_EVENT, UP_EVENT, false),
        });
    verifyEmbedderEvents(testShiftRightEvent(true, false, UP_EVENT, true), new KeyData[] {});
    verifyEmbedderEvents(
        testShiftRightEvent(true, true, UP_EVENT, true),
        new KeyData[] {
          buildShiftKeyData(SHIFT_RIGHT_EVENT, UP_EVENT, false),
        });

    // DOWN_EVENT, truePressed: false - skipped, because they're impossible.

    // DOWN_EVENT, truePressed: true

    verifyEmbedderEvents(
        testShiftRightEvent(false, false, DOWN_EVENT, true),
        new KeyData[] {
          buildShiftKeyData(SHIFT_RIGHT_EVENT, DOWN_EVENT, false),
        });
    verifyEmbedderEvents(
        testShiftRightEvent(false, true, DOWN_EVENT, true),
        new KeyData[] {
          buildShiftKeyData(SHIFT_RIGHT_EVENT, UP_EVENT, true),
          buildShiftKeyData(SHIFT_RIGHT_EVENT, DOWN_EVENT, false),
        });
    verifyEmbedderEvents(
        testShiftRightEvent(true, false, DOWN_EVENT, true),
        new KeyData[] {
          buildShiftKeyData(SHIFT_RIGHT_EVENT, DOWN_EVENT, false),
        });
    verifyEmbedderEvents(
        testShiftRightEvent(true, true, DOWN_EVENT, true),
        new KeyData[] {
          buildShiftKeyData(SHIFT_RIGHT_EVENT, UP_EVENT, true),
          buildShiftKeyData(SHIFT_RIGHT_EVENT, DOWN_EVENT, false),
        });
  }

  @Test
  public void synchronizeOtherModifiers() {
    // Test if other modifiers can be synchronized during events of ArrowLeft. Only the minimal
    // cases are used here since the full logic has been tested on ShiftLeft.
    final KeyboardTester tester = new KeyboardTester();
    final ArrayList<CallRecord> calls = new ArrayList<>();

    tester.recordEmbedderCallsTo(calls);
    tester.respondToTextInputWith(true); // Suppress redispatching

    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(
                ACTION_DOWN, SCAN_ARROW_LEFT, KEYCODE_DPAD_LEFT, 0, '\0', META_CTRL_ON)));
    assertEquals(calls.size(), 2);
    assertEmbedderEventEquals(
        calls.get(0).keyData, Type.kDown, PHYSICAL_CONTROL_LEFT, LOGICAL_CONTROL_LEFT, null, true);
    calls.clear();

    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(ACTION_UP, SCAN_ARROW_LEFT, KEYCODE_DPAD_LEFT, 0, '\0', 0)));
    assertEquals(calls.size(), 2);
    assertEmbedderEventEquals(
        calls.get(0).keyData, Type.kUp, PHYSICAL_CONTROL_LEFT, LOGICAL_CONTROL_LEFT, null, true);
    calls.clear();

    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(
                ACTION_DOWN, SCAN_ARROW_LEFT, KEYCODE_DPAD_LEFT, 0, '\0', META_ALT_ON)));
    assertEquals(calls.size(), 2);
    assertEmbedderEventEquals(
        calls.get(0).keyData, Type.kDown, PHYSICAL_ALT_LEFT, LOGICAL_ALT_LEFT, null, true);
    calls.clear();

    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(ACTION_UP, SCAN_ARROW_LEFT, KEYCODE_DPAD_LEFT, 0, '\0', 0)));
    assertEquals(calls.size(), 2);
    assertEmbedderEventEquals(
        calls.get(0).keyData, Type.kUp, PHYSICAL_ALT_LEFT, LOGICAL_ALT_LEFT, null, true);
    calls.clear();
  }

  @Test
  public void normalCapsLockEvents() {
    final KeyboardTester tester = new KeyboardTester();
    final ArrayList<CallRecord> calls = new ArrayList<>();

    tester.recordEmbedderCallsTo(calls);
    tester.respondToTextInputWith(true); // Suppress redispatching

    // The following two events seem to have weird metaStates that don't follow Android's
    // documentation (CapsLock flag set on down events) but are indeed observed on ChromeOS.

    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(
                ACTION_DOWN, SCAN_CAPS_LOCK, KEYCODE_CAPS_LOCK, 0, '\0', META_CAPS_LOCK_ON)));
    assertEquals(calls.size(), 1);
    assertEmbedderEventEquals(
        calls.get(0).keyData, Type.kDown, PHYSICAL_CAPS_LOCK, LOGICAL_CAPS_LOCK, null, false);
    calls.clear();

    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(ACTION_UP, SCAN_CAPS_LOCK, KEYCODE_CAPS_LOCK, 0, '\0', 0)));
    assertEquals(calls.size(), 1);
    assertEmbedderEventEquals(
        calls.get(0).keyData, Type.kUp, PHYSICAL_CAPS_LOCK, LOGICAL_CAPS_LOCK, null, false);
    calls.clear();

    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(
                ACTION_DOWN, SCAN_ARROW_LEFT, KEYCODE_DPAD_LEFT, 0, '\0', META_CAPS_LOCK_ON)));
    assertEquals(calls.size(), 1);
    assertEmbedderEventEquals(
        calls.get(0).keyData, Type.kDown, PHYSICAL_ARROW_LEFT, LOGICAL_ARROW_LEFT, null, false);
    calls.clear();

    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(
                ACTION_UP, SCAN_ARROW_LEFT, KEYCODE_DPAD_LEFT, 0, '\0', META_CAPS_LOCK_ON)));
    assertEquals(calls.size(), 1);
    assertEmbedderEventEquals(
        calls.get(0).keyData, Type.kUp, PHYSICAL_ARROW_LEFT, LOGICAL_ARROW_LEFT, null, false);
    calls.clear();

    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(
                ACTION_DOWN, SCAN_CAPS_LOCK, KEYCODE_CAPS_LOCK, 0, '\0', META_CAPS_LOCK_ON)));
    assertEquals(calls.size(), 1);
    assertEmbedderEventEquals(
        calls.get(0).keyData, Type.kDown, PHYSICAL_CAPS_LOCK, LOGICAL_CAPS_LOCK, null, false);
    calls.clear();

    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(ACTION_UP, SCAN_CAPS_LOCK, KEYCODE_CAPS_LOCK, 0, '\0', 0)));
    assertEquals(calls.size(), 1);
    assertEmbedderEventEquals(
        calls.get(0).keyData, Type.kUp, PHYSICAL_CAPS_LOCK, LOGICAL_CAPS_LOCK, null, false);
    calls.clear();

    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(ACTION_DOWN, SCAN_ARROW_LEFT, KEYCODE_DPAD_LEFT, 0, '\0', 0)));
    assertEquals(calls.size(), 1);
    assertEmbedderEventEquals(
        calls.get(0).keyData, Type.kDown, PHYSICAL_ARROW_LEFT, LOGICAL_ARROW_LEFT, null, false);
    calls.clear();

    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(ACTION_UP, SCAN_ARROW_LEFT, KEYCODE_DPAD_LEFT, 0, '\0', 0)));
    assertEquals(calls.size(), 1);
    assertEmbedderEventEquals(
        calls.get(0).keyData, Type.kUp, PHYSICAL_ARROW_LEFT, LOGICAL_ARROW_LEFT, null, false);
    calls.clear();
  }

  @Test
  public void synchronizeCapsLock() {
    final KeyboardTester tester = new KeyboardTester();
    final ArrayList<CallRecord> calls = new ArrayList<>();

    tester.recordEmbedderCallsTo(calls);
    tester.respondToTextInputWith(true); // Suppress redispatching

    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(
                ACTION_DOWN, SCAN_ARROW_LEFT, KEYCODE_DPAD_LEFT, 0, '\0', META_CAPS_LOCK_ON)));
    assertEquals(calls.size(), 3);
    assertEmbedderEventEquals(
        calls.get(0).keyData, Type.kDown, PHYSICAL_CAPS_LOCK, LOGICAL_CAPS_LOCK, null, true);
    assertEmbedderEventEquals(
        calls.get(1).keyData, Type.kUp, PHYSICAL_CAPS_LOCK, LOGICAL_CAPS_LOCK, null, true);
    assertEmbedderEventEquals(
        calls.get(2).keyData, Type.kDown, PHYSICAL_ARROW_LEFT, LOGICAL_ARROW_LEFT, null, false);
    calls.clear();

    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(
                ACTION_DOWN, SCAN_CAPS_LOCK, KEYCODE_CAPS_LOCK, 0, '\0', META_CAPS_LOCK_ON)));
    assertEquals(calls.size(), 1);
    assertEmbedderEventEquals(
        calls.get(0).keyData, Type.kDown, PHYSICAL_CAPS_LOCK, LOGICAL_CAPS_LOCK, null, false);
    calls.clear();

    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(
                ACTION_UP, SCAN_ARROW_LEFT, KEYCODE_DPAD_LEFT, 0, '\0', META_CAPS_LOCK_ON)));
    assertEquals(calls.size(), 3);
    assertEmbedderEventEquals(
        calls.get(0).keyData, Type.kUp, PHYSICAL_CAPS_LOCK, LOGICAL_CAPS_LOCK, null, true);
    assertEmbedderEventEquals(
        calls.get(1).keyData, Type.kDown, PHYSICAL_CAPS_LOCK, LOGICAL_CAPS_LOCK, null, true);
    assertEmbedderEventEquals(
        calls.get(2).keyData, Type.kUp, PHYSICAL_ARROW_LEFT, LOGICAL_ARROW_LEFT, null, false);
    calls.clear();

    assertEquals(
        true,
        tester.keyboardManager.handleEvent(
            new FakeKeyEvent(ACTION_UP, SCAN_CAPS_LOCK, KEYCODE_CAPS_LOCK, 0, '\0', 0)));
    assertEquals(calls.size(), 1);
    assertEmbedderEventEquals(
        calls.get(0).keyData, Type.kUp, PHYSICAL_CAPS_LOCK, LOGICAL_CAPS_LOCK, null, false);
    calls.clear();
  }
}
