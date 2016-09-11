/*
 * Copyright Sean Owen
 */

package com.srowen.bs.android.nfc;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.Parcelable;

import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * Support for NFC tag scanning.
 */
public final class NFCInterface {
  
  private static final short[] TNFS = {
      NdefRecord.TNF_UNKNOWN,
      NdefRecord.TNF_ABSOLUTE_URI,
      NdefRecord.TNF_EMPTY,
      NdefRecord.TNF_EXTERNAL_TYPE,
      NdefRecord.TNF_MIME_MEDIA,
      NdefRecord.TNF_UNCHANGED,
      NdefRecord.TNF_WELL_KNOWN,
  };
  private static final String[] TNF_NAMES = {
      "UNKNOWN",
      "ABSOLUTE_URI",
      "EMPTY",
      "EXTERNAL_TYPE",
      "MIME_MEDIA",
      "UNCHANGED",
      "WELL_KNOWN",
  };
  private static final byte[][] TYPES = {
      NdefRecord.RTD_ALTERNATIVE_CARRIER,
      NdefRecord.RTD_HANDOVER_CARRIER,
      NdefRecord.RTD_HANDOVER_REQUEST,
      NdefRecord.RTD_HANDOVER_SELECT,
      NdefRecord.RTD_SMART_POSTER,
      NdefRecord.RTD_TEXT,
      NdefRecord.RTD_URI,
  };
  private static final String[] TYPE_NAMES = {
      "ALTERNATIVE_CARRIER",
      "HANDOVER_CARRIER",
      "HANDOVER_REQUEST",
      "HANDOVER_SELECT",
      "SMART_POSTER",
      "TEXT",
      "URI",
  };
  private static final String[] URI_PREFIXES = {
      null, "http://www.", "https://www.", "http://", "https://",
      "tel:", "mailto:", "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://",
      "sftp://", "smb://", "nfs://", "ftp://", "dav://",
      "news:", "telnet://", "imap:", "rtsp://", "urn:",
      "pop:", "sip:", "sips:", "tftp:", "btspp://",
      "btl2cap://", "btgoep://", "tcpobex://", "irdaobex://", "file://",
      "urn:epc:id:", "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:", "urn:epc:",
      "urn:nfc:",
  };
  private static final Charset UTF_8 = Charset.forName("UTF-8");
  private static final Charset UTF_16 = Charset.forName("UTF-16");

  private static final IntentFilter[] INTENT_FILTERS = {
      new IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED),
      new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
      new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED),
  };

  private NFCInterface() {
  }

  public static void listenForNFC(Activity activity) {
    NfcAdapter adapter = NfcAdapter.getDefaultAdapter(activity);
    if (adapter == null) {
      return;
    }
    Intent intent = new Intent(activity, activity.getClass());
    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent pendingIntent =
        PendingIntent.getActivity(activity,
                                  0,
                                  intent,
                                  PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_ONE_SHOT);
    adapter.enableForegroundDispatch(activity, pendingIntent, INTENT_FILTERS, null);
  }

  public static void unlistenForNFC(Activity activity) {
    NfcAdapter adapter = NfcAdapter.getDefaultAdapter(activity);
    if (adapter == null) {
      return;
    }
    adapter.disableForegroundDispatch(activity);
  }

  public static String parseNFCStringPayload(Bundle extras) {

    StringBuilder result = new StringBuilder();

    byte[] nfcID = extras.getByteArray(NfcAdapter.EXTRA_ID);
    if (nfcID != null) {
      result.append("NFC ID: ").append(toHexString(nfcID)).append('\n');
    }

    Tag tag = (Tag) extras.get(NfcAdapter.EXTRA_TAG);
    if (tag != null) {
      result.append(tag).append('\n');
    }

    Parcelable[] messages = extras.getParcelableArray(NfcAdapter.EXTRA_NDEF_MESSAGES);
    if (messages != null) {
      for (Parcelable message : messages) {
        NdefRecord[] records = ((NdefMessage) message).getRecords();
        if (records != null) {
          result.append("NDEF Messages:\n");
          for (NdefRecord record : records) {
            byte[] type = record.getType();
            if (isType(type, NdefRecord.RTD_TEXT)) {
              // Special case: only return text
              return decodeTextPayload(record.getPayload());
            }
            if (isType(type, NdefRecord.RTD_URI)) {
              // Special case: only return URI
              return decodeURIPayload(record.getPayload());
            }
            result.append(' ').append(tnfToName(record.getTnf())).append(" : ")
                .append('(').append(typeToName(type)).append(")\n");
          }
        }
      }
    }

    return result.toString();
  }
  
  private static String tnfToName(short tnf) {
    for (int i = 0; i < TNFS.length; i++) {
      if (TNFS[i] == tnf) {
        return TNF_NAMES[i];
      }
    }
    return TNF_NAMES[0];
  }

  private static String typeToName(byte[] type) {
    if (type != null) {
      for (int i = 0; i < TYPES.length; i++) {
        if (isType(TYPES[i], type)) {
          return TYPE_NAMES[i];
        }
      }
    }
    return "UNKNOWN";
  }
  
  private static boolean isType(byte[] value, byte[] type) {
    return value != null && Arrays.equals(value, type);
  }

  private static String decodeTextPayload(byte[] payload) {
    byte statusByte = payload[0];
    boolean isUTF16 = (statusByte & 0x80) != 0;
    Charset encoding = isUTF16 ? UTF_16 : UTF_8;
    int languageLength = statusByte & 0x1F;
    //String language = new String(payload, 1, languageLength, US_ASCII);
    return new String(payload, 1 + languageLength, payload.length - languageLength - 1, encoding);
  }

  private static String decodeURIPayload(byte[] payload) {
    int identifierCode = payload[0] & 0xFF;
    String prefix = null;
    if (identifierCode < URI_PREFIXES.length) {
      prefix = URI_PREFIXES[identifierCode];
    }
    String restOfURI = new String(payload, 1, payload.length - 1, UTF_8);
    return prefix == null ? restOfURI : prefix + restOfURI;
  }

  private static String toHexString(byte[] bytes) {
    StringBuilder result = new StringBuilder(2 * bytes.length);
    for (byte b : bytes) {
      int value = b & 0xFF;
      if (value <= 0x0F) {
        result.append('0');
      }
      result.append(Integer.toHexString(value));
    }
    return result.toString();
  }

}
