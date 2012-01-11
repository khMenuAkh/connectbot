/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2010 Kenny Root, Jeffrey Sharkey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sk.vx.connectbot.service;

import java.io.IOException;

import sk.vx.connectbot.R;
import sk.vx.connectbot.TerminalView;
import sk.vx.connectbot.bean.SelectionArea;
import sk.vx.connectbot.util.PreferenceConstants;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Configuration;
import android.preference.PreferenceManager;
import android.text.ClipboardManager;
import android.text.Editable;
import android.text.method.CharacterPickerDialog;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnKeyListener;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.Toast;
import de.mud.terminal.VDUBuffer;
import de.mud.terminal.vt320;

/**
 * @author kenny
 *
 */
@SuppressWarnings("deprecation") // for ClipboardManager
public class TerminalKeyListener implements OnKeyListener, OnSharedPreferenceChangeListener {
	private static final String TAG = "ConnectBot.OnKeyListener";

	public final static int META_CTRL_ON = 0x01;
	public final static int META_CTRL_LOCK = 0x02;
	public final static int META_ALT_ON = 0x04;
	public final static int META_ALT_LOCK = 0x08;
	public final static int META_SHIFT_ON = 0x10;
	public final static int META_SHIFT_LOCK = 0x20;
	public final static int META_SLASH = 0x40;
	public final static int META_TAB = 0x80;

	// The bit mask of momentary and lock states for each
	public final static int META_CTRL_MASK = META_CTRL_ON | META_CTRL_LOCK;
	public final static int META_ALT_MASK = META_ALT_ON | META_ALT_LOCK;
	public final static int META_SHIFT_MASK = META_SHIFT_ON | META_SHIFT_LOCK;

	// backport constants from api level 11
	public final static int KEYCODE_ESCAPE = 111;
	public final static int HC_META_CTRL_ON = 4096;

	// All the transient key codes
	public final static int META_TRANSIENT = META_CTRL_ON | META_ALT_ON
			| META_SHIFT_ON;

	private final TerminalManager manager;
	private final TerminalBridge bridge;
	private final VDUBuffer buffer;

	private String keymode = null;
	private boolean hardKeyboard = false;
	private String customKeyboard = null;

	private int metaState = 0;

	private int mDeadKey = 0;

	// TODO add support for the new API.
	private ClipboardManager clipboard = null;

	private boolean selectingForCopy = false;
	private final SelectionArea selectionArea;

	private String encoding;

	private final SharedPreferences prefs;

	private Toast debugToast = null;

	public TerminalKeyListener(TerminalManager manager,
			TerminalBridge bridge,
			VDUBuffer buffer,
			String encoding) {
		this.manager = manager;
		this.bridge = bridge;
		this.buffer = buffer;
		this.encoding = encoding;

		selectionArea = new SelectionArea();

		prefs = PreferenceManager.getDefaultSharedPreferences(manager);
		prefs.registerOnSharedPreferenceChangeListener(this);

		hardKeyboard = (manager.res.getConfiguration().keyboard
				== Configuration.KEYBOARD_QWERTY);

		updateKeymode();
		updateCustomKeymap();
	}

	/**
	 * Handle onKey() events coming down from a {@link TerminalView} above us.
	 * Modify the keys to make more sense to a host then pass it to the transport.
	 */
	public boolean onKey(View v, int keyCode, KeyEvent event) {
		try {
			final boolean hardKeyboardHidden = manager.hardKeyboardHidden;

			// Ignore all key-up events except for the special keys
			if (event.getAction() == KeyEvent.ACTION_UP) {

				// There's nothing here for virtual keyboard users.
				if (!hardKeyboard || (hardKeyboard && hardKeyboardHidden))
					return false;

				// skip keys if we aren't connected yet or have been disconnected
				if (bridge.isDisconnected() || bridge.transport == null)
					return false;

				// if keycode debugging enabled, log and print the pressed key
				if (prefs.getBoolean(PreferenceConstants.DEBUG_KEYCODES, false)) {
					String keyCodeString = String.format(": %d", keyCode);
					String toastText = v.getContext().getString(R.string.keycode_pressed) + keyCodeString;
					if (debugToast == null)
						debugToast = Toast.makeText(v.getContext(), toastText, Toast.LENGTH_LONG);
					else
						debugToast.setText(toastText);
					debugToast.show();
				}

				if (customKeyboard.equals(PreferenceConstants.CUSTOM_KEYMAP_DISABLED)) {
					// Not applicable for customized keyboards
					if (PreferenceConstants.KEYMODE_RIGHT.equals(keymode)) {
						if (keyCode == KeyEvent.KEYCODE_ALT_RIGHT
								&& (metaState & META_SLASH) != 0) {
							metaState &= ~(META_SLASH | META_TRANSIENT);
							bridge.transport.write('/');
							return true;
						} else if (keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT
								&& (metaState & META_TAB) != 0) {
							metaState &= ~(META_TAB | META_TRANSIENT);
							bridge.transport.write(0x09);
							return true;
						}
					} else if (PreferenceConstants.KEYMODE_LEFT.equals(keymode)) {
						if (keyCode == KeyEvent.KEYCODE_ALT_LEFT
								&& (metaState & META_SLASH) != 0) {
							metaState &= ~(META_SLASH | META_TRANSIENT);
							bridge.transport.write('/');
							return true;
						} else if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT
								&& (metaState & META_TAB) != 0) {
							metaState &= ~(META_TAB | META_TRANSIENT);
							bridge.transport.write(0x09);
							return true;
						}
					}
				}

				return false;
			}

			// check for terminal resizing keys
			if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
				bridge.increaseFontSize();
				return true;
			} else if(keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
				bridge.decreaseFontSize();
				return true;
			}

			// skip keys if we aren't connected yet or have been disconnected
			if (bridge.isDisconnected() || bridge.transport == null)
				return false;

			bridge.resetScrollPosition();

			if (keyCode == KeyEvent.KEYCODE_UNKNOWN &&
					event.getAction() == KeyEvent.ACTION_MULTIPLE) {
				byte[] input = event.getCharacters().getBytes(encoding);
				bridge.transport.write(input);
				return true;
			}

			int curMetaState = event.getMetaState();
			final int orgMetaState = curMetaState;

			if ((metaState & META_SHIFT_MASK) != 0) {
				curMetaState |= KeyEvent.META_SHIFT_ON;
			}

			if ((metaState & META_ALT_MASK) != 0) {
				curMetaState |= KeyEvent.META_ALT_ON;
			}

			int key = event.getUnicodeChar(curMetaState);
			// no hard keyboard?  ALT-k should pass through to below
			if ((orgMetaState & KeyEvent.META_ALT_ON) != 0 &&
					(!hardKeyboard || hardKeyboardHidden)) {
				key = 0;
			}

			if ((key & KeyCharacterMap.COMBINING_ACCENT) != 0) {
				mDeadKey = key & KeyCharacterMap.COMBINING_ACCENT_MASK;
				return true;
			}

			if (mDeadKey != 0) {
				key = KeyCharacterMap.getDeadChar(mDeadKey, keyCode);
				mDeadKey = 0;
			}

			final boolean printing = (key != 0 && keyCode != KeyEvent.KEYCODE_ENTER);

			//Show up the CharacterPickerDialog when the SYM key is pressed
			if( (keyCode == KeyEvent.KEYCODE_SYM || keyCode == KeyEvent.KEYCODE_PICTSYMBOLS ||
					key == KeyCharacterMap.PICKER_DIALOG_INPUT) && v != null) {
				showCharPickerDialog(v);
				if(metaState == 4) { // reset fn-key state
					metaState = 0;
					bridge.redraw();
				}
				return true;
			}

			// otherwise pass through to existing session
			// print normal keys
			if (printing) {
				metaState &= ~(META_SLASH | META_TAB);

				// Remove shift and alt modifiers
				final int lastMetaState = metaState;
				metaState &= ~(META_SHIFT_ON | META_ALT_ON);
				if (metaState != lastMetaState) {
					bridge.redraw();
				}

				if ((metaState & META_CTRL_MASK) != 0) {
					metaState &= ~META_CTRL_ON;
					bridge.redraw();

					// If there is no hard keyboard or there is a hard keyboard currently hidden,
					// CTRL-1 through CTRL-9 will send F1 through F9
					if ((!hardKeyboard || (hardKeyboard && hardKeyboardHidden))
							&& sendFunctionKey(keyCode))
						return true;

					key = keyAsControl(key);
				}

				// handle pressing f-keys
				if ((hardKeyboard && !hardKeyboardHidden)
						&& (curMetaState & KeyEvent.META_SHIFT_ON) != 0
						&& sendFunctionKey(keyCode))
					return true;

				if (key < 0x80)
					bridge.transport.write(key);
				else
					// TODO write encoding routine that doesn't allocate each time
					bridge.transport.write(new String(Character.toChars(key))
							.getBytes(encoding));

				return true;
			}

			// send ctrl and meta-keys as appropriate
			if (!hardKeyboard || hardKeyboardHidden) {
				int k = event.getUnicodeChar(0);
				int k0 = k;
				boolean sendCtrl = false;
				boolean sendMeta = false;
				if (k != 0) {
					if ((orgMetaState & HC_META_CTRL_ON) != 0) {
						k = keyAsControl(k);
						if (k != k0)
							sendCtrl = true;
						// send F1-F10 via CTRL-1 through CTRL-0
						if (!sendCtrl && sendFunctionKey(keyCode))
							return true;
					} else if ((orgMetaState & KeyEvent.META_ALT_ON) != 0) {
						sendMeta = true;
						sendEscape();
					}
					if (sendMeta || sendCtrl) {
						bridge.transport.write(k);
						return true;
					}
				}
			}
			// try handling keymode shortcuts
			if (hardKeyboard && !hardKeyboardHidden &&
					event.getRepeatCount() == 0) {
				if (customKeyboard.equals(PreferenceConstants.CUSTOM_KEYMAP_DISABLED)) {
					// No keyboard customization
					if (PreferenceConstants.KEYMODE_RIGHT.equals(keymode)) {
						switch (keyCode) {
						case KeyEvent.KEYCODE_ALT_RIGHT:
							metaState |= META_SLASH;
							return true;
						case KeyEvent.KEYCODE_SHIFT_RIGHT:
							metaState |= META_TAB;
							return true;
						case KeyEvent.KEYCODE_SHIFT_LEFT:
							metaPress(META_SHIFT_ON);
							return true;
						case KeyEvent.KEYCODE_ALT_LEFT:
							metaPress(META_ALT_ON);
							return true;
						}
					} else if (PreferenceConstants.KEYMODE_LEFT.equals(keymode)) {
						switch (keyCode) {
						case KeyEvent.KEYCODE_ALT_LEFT:
							metaState |= META_SLASH;
							return true;
						case KeyEvent.KEYCODE_SHIFT_LEFT:
							metaState |= META_TAB;
							return true;
						case KeyEvent.KEYCODE_SHIFT_RIGHT:
							metaPress(META_SHIFT_ON);
							return true;
						case KeyEvent.KEYCODE_ALT_RIGHT:
							metaPress(META_ALT_ON);
							return true;
						}
					}
				} else {
					// Keyboard Customization
					switch (keyCode) {
					case KeyEvent.KEYCODE_SWITCH_CHARSET:
						if (customKeyboard.equals(PreferenceConstants.CUSTOM_KEYMAP_SE_XPPRO)) {
							ctrlKeySpecial();
							return true;
						}
					case KeyEvent.KEYCODE_S:
						if (customKeyboard.equals(PreferenceConstants.CUSTOM_KEYMAP_SE_XPPRO)) {
							bridge.transport.write(0x7C);
							metaState &= ~META_TRANSIENT;
							bridge.redraw();
							return true;
						}
					case KeyEvent.KEYCODE_Z:
						if (customKeyboard.equals(PreferenceConstants.CUSTOM_KEYMAP_SE_XPPRO)) {
							bridge.transport.write(0x5C);
							metaState &= ~META_TRANSIENT;
							bridge.redraw();
							return true;
						}
					}
				}

				switch (keyCode) {
				case KeyEvent.KEYCODE_ALT_RIGHT:
				case KeyEvent.KEYCODE_ALT_LEFT:
					metaPress(META_ALT_ON);
					return true;
				case KeyEvent.KEYCODE_SHIFT_LEFT:
				case KeyEvent.KEYCODE_SHIFT_RIGHT:
					metaPress(META_SHIFT_ON);
					return true;
				}
			}

			// look for special chars
			switch(keyCode) {
			case KEYCODE_ESCAPE:
				sendEscape();
				return true;
			case KeyEvent.KEYCODE_TAB:
				bridge.transport.write(0x09);
				return true;
			case KeyEvent.KEYCODE_CAMERA:

				// check to see which shortcut the camera button triggers
				String camera = manager.prefs.getString(
						PreferenceConstants.CAMERA,
						PreferenceConstants.CAMERA_SCREEN_CAPTURE);
				if(PreferenceConstants.CAMERA_SCREEN_CAPTURE.equals(camera)) {
					bridge.captureScreen();
				} else if (PreferenceConstants.CAMERA_CTRLA_SPACE.equals(camera)) {
					bridge.transport.write(0x01);
					bridge.transport.write(' ');
				} else if(PreferenceConstants.CAMERA_CTRLA.equals(camera)) {
					bridge.transport.write(0x01);
				} else if(PreferenceConstants.CAMERA_ESC.equals(camera)) {
					((vt320)buffer).keyTyped(vt320.KEY_ESCAPE, ' ', 0);
				} else if(PreferenceConstants.CAMERA_ESC_A.equals(camera)) {
					((vt320)buffer).keyTyped(vt320.KEY_ESCAPE, ' ', 0);
					bridge.transport.write('a');
				}

				break;

			case KeyEvent.KEYCODE_DEL:
				if ((metaState & META_ALT_MASK) != 0) {
					((vt320) buffer).keyPressed(vt320.KEY_DELETE, ' ',
						getStateForBuffer());
				} else {
					((vt320) buffer).keyPressed(vt320.KEY_BACK_SPACE, ' ',
						getStateForBuffer());
				}
				metaState &= ~META_TRANSIENT;
				return true;
			case KeyEvent.KEYCODE_ENTER:
				((vt320)buffer).keyTyped(vt320.KEY_ENTER, ' ', 0);
				metaState &= ~META_TRANSIENT;
				return true;

			case KeyEvent.KEYCODE_DPAD_LEFT:
				if (selectingForCopy) {
					selectionArea.decrementColumn();
					bridge.redraw();
				} else {
					if ((metaState & META_ALT_MASK) != 0) {
						((vt320) buffer).keyPressed(vt320.KEY_HOME, ' ',
								getStateForBuffer());
					} else {
						((vt320) buffer).keyPressed(vt320.KEY_LEFT, ' ',
								getStateForBuffer());
					}
					metaState &= ~META_TRANSIENT;
					bridge.tryKeyVibrate();
				}
				return true;

			case KeyEvent.KEYCODE_DPAD_UP:
				if (selectingForCopy) {
					selectionArea.decrementRow();
					bridge.redraw();
				} else {
					if ((metaState & META_ALT_MASK) != 0) {
						((vt320)buffer).keyPressed(vt320.KEY_PAGE_UP, ' ',
								getStateForBuffer());
					} else {
						((vt320) buffer).keyPressed(vt320.KEY_UP, ' ',
								getStateForBuffer());
					}
					metaState &= ~META_TRANSIENT;
					bridge.tryKeyVibrate();
				}
				return true;

			case KeyEvent.KEYCODE_DPAD_DOWN:
				if (selectingForCopy) {
					selectionArea.incrementRow();
					bridge.redraw();
				} else {
					if ((metaState & META_ALT_MASK) != 0) {
						((vt320)buffer).keyPressed(vt320.KEY_PAGE_DOWN, ' ',
								getStateForBuffer());
					} else {
						((vt320) buffer).keyPressed(vt320.KEY_DOWN, ' ',
								getStateForBuffer());
					}
					metaState &= ~META_TRANSIENT;
					bridge.tryKeyVibrate();
				}
				return true;

			case KeyEvent.KEYCODE_DPAD_RIGHT:
				if (selectingForCopy) {
					selectionArea.incrementColumn();
					bridge.redraw();
				} else {
					if ((metaState & META_ALT_MASK) != 0) {
						((vt320) buffer).keyPressed(vt320.KEY_END, ' ',
								getStateForBuffer());
					} else {
						((vt320) buffer).keyPressed(vt320.KEY_RIGHT, ' ',
								getStateForBuffer());
					}
					metaState &= ~META_TRANSIENT;
					bridge.tryKeyVibrate();
				}
				return true;

			case KeyEvent.KEYCODE_DPAD_CENTER:
				ctrlKeySpecial();
				return true;
			}

		} catch (IOException e) {
			Log.e(TAG, "Problem while trying to handle an onKey() event", e);
			try {
				bridge.transport.flush();
			} catch (IOException ioe) {
				Log.d(TAG, "Our transport was closed, dispatching disconnect event");
				bridge.dispatchDisconnect(false);
			}
		} catch (NullPointerException npe) {
			Log.d(TAG, "Input before connection established ignored.");
			return true;
		}

		return false;
	}

	public int keyAsControl(int key) {
		// Support CTRL-a through CTRL-z
		if (key >= 0x61 && key <= 0x7A)
			key -= 0x60;
		// Support CTRL-A through CTRL-_
		else if (key >= 0x41 && key <= 0x5F)
			key -= 0x40;
		// CTRL-space sends NULL
		else if (key == 0x20)
			key = 0x00;
		// CTRL-? sends DEL
		else if (key == 0x3F)
			key = 0x7F;
		return key;
	}

	public void sendEscape() {
		((vt320)buffer).keyTyped(vt320.KEY_ESCAPE, ' ', 0);
	}

	/**
	 * @param key
	 * @return successful
	 */
	private boolean sendFunctionKey(int keyCode) {
		switch (keyCode) {
		case KeyEvent.KEYCODE_1:
			((vt320) buffer).keyPressed(vt320.KEY_F1, ' ', 0);
			return true;
		case KeyEvent.KEYCODE_2:
			((vt320) buffer).keyPressed(vt320.KEY_F2, ' ', 0);
			return true;
		case KeyEvent.KEYCODE_3:
			((vt320) buffer).keyPressed(vt320.KEY_F3, ' ', 0);
			return true;
		case KeyEvent.KEYCODE_4:
			((vt320) buffer).keyPressed(vt320.KEY_F4, ' ', 0);
			return true;
		case KeyEvent.KEYCODE_5:
			((vt320) buffer).keyPressed(vt320.KEY_F5, ' ', 0);
			return true;
		case KeyEvent.KEYCODE_6:
			((vt320) buffer).keyPressed(vt320.KEY_F6, ' ', 0);
			return true;
		case KeyEvent.KEYCODE_7:
			((vt320) buffer).keyPressed(vt320.KEY_F7, ' ', 0);
			return true;
		case KeyEvent.KEYCODE_8:
			((vt320) buffer).keyPressed(vt320.KEY_F8, ' ', 0);
			return true;
		case KeyEvent.KEYCODE_9:
			((vt320) buffer).keyPressed(vt320.KEY_F9, ' ', 0);
			return true;
		case KeyEvent.KEYCODE_0:
			((vt320) buffer).keyPressed(vt320.KEY_F10, ' ', 0);
			return true;
		default:
			return false;
		}
	}

	/**
	 * Handle meta key presses where the key can be locked on.
	 * <p>
	 * 1st press: next key to have meta state<br />
	 * 2nd press: meta state is locked on<br />
	 * 3rd press: disable meta state
	 *
	 * @param code
	 */
	public void metaPress(int code) {
		if ((metaState & (code << 1)) != 0) {
			metaState &= ~(code << 1);
		} else if ((metaState & code) != 0) {
			metaState &= ~code;
			metaState |= code << 1;
		} else
			metaState |= code;
		bridge.redraw();
	}

	public void setTerminalKeyMode(String keymode) {
		this.keymode = keymode;
	}

	private int getStateForBuffer() {
		int bufferState = 0;

		if ((metaState & META_CTRL_MASK) != 0)
			bufferState |= vt320.KEY_CONTROL;
		if ((metaState & META_SHIFT_MASK) != 0)
			bufferState |= vt320.KEY_SHIFT;
		if ((metaState & META_ALT_MASK) != 0)
			bufferState |= vt320.KEY_ALT;

		return bufferState;
	}

	public int getMetaState() {
		return metaState;
	}

	public int getDeadKey() {
		return mDeadKey;
	}

	public void setClipboardManager(ClipboardManager clipboard) {
		this.clipboard = clipboard;
	}

	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
			String key) {
		if (PreferenceConstants.KEYMODE.equals(key)) {
			updateKeymode();
		} else if (PreferenceConstants.CUSTOM_KEYMAP.equals(key)) {
			updateCustomKeymap();
		}
	}

	private void updateKeymode() {
		keymode = prefs.getString(PreferenceConstants.KEYMODE, PreferenceConstants.KEYMODE_RIGHT);
	}

	private void updateCustomKeymap() {
		customKeyboard = prefs.getString(PreferenceConstants.CUSTOM_KEYMAP,
				PreferenceConstants.CUSTOM_KEYMAP_DISABLED);
	}

	public void setCharset(String encoding) {
		this.encoding = encoding;
	}

	private String getPickerString() {
		final String defaultSet = "~\\^()[]{}<>|/:_;,.!@#$%&*?\"'-+=";
		String set = prefs.getString(PreferenceConstants.PICKER_STRING,defaultSet);
		if (set == null || set.equals("")) {
			set = defaultSet;
		}
		return set;
	}

	public boolean showCharPickerDialog(View v) {
		CharSequence str = "";
		Editable content = Editable.Factory.getInstance().newEditable(str);

		CharacterPickerDialog cpd = new CharacterPickerDialog(v.getContext(),
				v, content, getPickerString(), true) {
			private void writeChar(CharSequence result) {
				try {
					bridge.transport.write(result.toString().getBytes(encoding));
				} catch (IOException e) {
					Log.e(TAG, "Problem with the CharacterPickerDialog", e);
				}
				if (!prefs.getBoolean(PreferenceConstants.PICKER_KEEP_OPEN,false))
						dismiss();
			}

			@Override
			public void onItemClick(AdapterView p, View v, int pos, long id) {
				String result = String.valueOf(getPickerString().charAt(pos));
				writeChar(result);
			}

			@Override
			public void onClick(View v) {
				if (v instanceof Button) {
					CharSequence result = ((Button) v).getText();
					if (result.equals(""))
						dismiss();
					else
						writeChar(result);
				}
			}

			@Override
			public boolean onKeyDown(int keyCode, KeyEvent event) {
				if (keyCode == KeyEvent.KEYCODE_SYM || keyCode == KeyEvent.KEYCODE_PICTSYMBOLS) {
					dismiss();
					return true;
				}
				return super.onKeyDown(keyCode, event);
			}
		};

		cpd.show();
		return true;
	}

	private void ctrlKeySpecial() {
		if (selectingForCopy) {
			if (selectionArea.isSelectingOrigin())
				selectionArea.finishSelectingOrigin();
			else {
				if (clipboard != null) {
					// copy selected area to clipboard
					String copiedText = selectionArea.copyFrom(buffer);

					clipboard.setText(copiedText);
					// XXX STOPSHIP
//					manager.notifyUser(manager.getString(
//							R.string.console_copy_done,
//							copiedText.length()));

					selectingForCopy = false;
					selectionArea.reset();
				}
			}
		} else {
			if ((metaState & META_CTRL_ON) != 0) {
				sendEscape();
				metaState &= ~META_CTRL_ON;
			} else
				metaPress(META_CTRL_ON);
		}

		bridge.redraw();
	}
}
