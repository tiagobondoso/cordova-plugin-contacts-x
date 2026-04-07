package de.einfachhans.ContactsX;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;
import android.provider.ContactsContract;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;

import org.apache.cordova.LOG;
import org.apache.cordova.PermissionHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * This class echoes a string called from JavaScript.
 */
public class ContactsX extends CordovaPlugin {

    private CallbackContext _callbackContext;
    private final String LOG_TAG = "ContactsX";

    public static final String READ = Manifest.permission.READ_CONTACTS;
    public static final String WRITE = Manifest.permission.WRITE_CONTACTS;

    private static final String EMAIL_REGEXP = ".+@.+\\.+.+"; /* <anything>@<anything>.<anything>*/

    public static final int REQ_CODE_PERMISSIONS = 0;
    public static final int REQ_CODE_PICK = 2;
    public static final int REQ_CODE_PICK_PERMISSION = 3;

    private PhoneNumberUtil phoneUtil = PhoneNumberUtil.getInstance();

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
        this._callbackContext = callbackContext;

        try {
            if (action.equals("find")) {
                if (PermissionHelper.hasPermission(this, READ)) {
                    this.find(args);
                } else {
                    returnError(ContactsXErrorCodes.PermissionDenied);
                }
            } else if (action.equals("pick")) {
                // The system picker UI needs no permission to open, but reading
                // the selected contact's data afterwards requires READ_CONTACTS.
                // Request it now if not yet granted; the picker launches once granted.
                if (PermissionHelper.hasPermission(this, READ)) {
                    this.pick();
                } else {
                    PermissionHelper.requestPermission(this, REQ_CODE_PICK_PERMISSION, READ);
                }
            } else if (action.equals("save")) {
                if(PermissionHelper.hasPermission(this, WRITE)) {
                    this.save(args);
                } else {
                    returnError(ContactsXErrorCodes.PermissionDenied);
                }
            } else if(action.equals("delete")) {
                if(PermissionHelper.hasPermission(this, WRITE)) {
                    this.delete(args);
                } else {
                    returnError(ContactsXErrorCodes.PermissionDenied);
                }
            } else if (action.equals("hasPermission")) {
                this.hasPermission();
            } else if (action.equals("requestPermission")) {
                boolean write = args.optBoolean(0);
                this.requestPermission(write);
            } else {
                returnError(ContactsXErrorCodes.UnsupportedAction);
            }
        } catch (JSONException exception) {
            returnError(ContactsXErrorCodes.WrongJsonObject);
        } catch (Exception exception) {
            returnError(ContactsXErrorCodes.UnknownError, exception.getMessage());
        }

        return true;
    }

    public void onActivityResult(int requestCode, int resultCode, final Intent intent) {
        if (requestCode == REQ_CODE_PICK) {
            if (resultCode == Activity.RESULT_OK) {
                // Move all DB work off the main thread to avoid ANR crashes.
                this.cordova.getThreadPool().execute(() -> {
                    try {
                        Uri contactUri = intent.getData();
                        LOG.d(LOG_TAG, "pick onActivityResult URI=" + contactUri);
                        if (contactUri == null) {
                            returnError(ContactsXErrorCodes.UnknownError, "No contact URI returned");
                            return;
                        }

                        // Resolve the CONTACT_ID directly from the URI the picker gave us.
                        // The system grants temporary read access to this URI even without READ_CONTACTS.
                        Cursor idCursor = this.cordova.getActivity().getContentResolver().query(
                                contactUri,
                                new String[]{ContactsContract.Contacts._ID},
                                null, null, null);

                        if (idCursor == null || !idCursor.moveToFirst()) {
                            if (idCursor != null) idCursor.close();
                            returnError(ContactsXErrorCodes.UnknownError, "Could not resolve contact from URI");
                            return;
                        }

                        String contactId = idCursor.getString(idCursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID));
                        idCursor.close();
                        LOG.d(LOG_TAG, "pick resolved contactId=" + contactId);

                        JSONObject contact = buildContactFromPickerUri(contactUri, contactId);
                        if (contact != null) {
                            LOG.d(LOG_TAG, "pick success contact=" + contact.toString());
                            // Send as a serialised JSON string so the JS layer
                            // can JSON.parse it — consistent with iOS behaviour.
                            this._callbackContext.success(contact.toString());
                        } else {
                            returnError(ContactsXErrorCodes.UnknownError, "Could not load contact data");
                        }
                    } catch (Exception e) {
                        LOG.e(LOG_TAG, "pick onActivityResult exception: " + e.getMessage(), e);
                        returnError(ContactsXErrorCodes.UnknownError, e.getMessage());
                    }
                });
            } else {
                returnError(ContactsXErrorCodes.UnknownError);

            }
        }
    }

    public void onRequestPermissionResult(int requestCode, String[] permissions,
                                          int[] grantResults) throws JSONException {
        if (requestCode == REQ_CODE_PICK_PERMISSION) {
            if (grantResults.length > 0 &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // Permission just granted — now open the picker
                this.pick();
            } else {
                returnError(ContactsXErrorCodes.PermissionDenied, "READ_CONTACTS permission denied");
            }
        } else {
            this.hasPermission();
        }
    }

    private void find(JSONArray args) throws JSONException {
        ContactsXFindOptions options = new ContactsXFindOptions(args.optJSONObject(0));

        this.cordova.getThreadPool().execute(() -> {

            ContentResolver contentResolver = this.cordova.getContext().getContentResolver();

            ArrayList<String> projection = this.getProjection(options);
            ArrayList<String> selectionArgs = this.getSelectionArgs(options);
            StringBuilder questionMarks = new StringBuilder();
            for (String s : selectionArgs) {
                if (selectionArgs.indexOf(s) == selectionArgs.size() - 1) {
                    questionMarks.append("?");
                } else {
                    questionMarks.append("?, ");
                }
            }
            String selection = ContactsContract.Data.MIMETYPE + " in (" + questionMarks.toString() + ")";

            Cursor contactsCursor = contentResolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    projection.toArray(new String[0]),
                    selection,
                    selectionArgs.toArray(new String[0]),
                    null
            );

            JSONArray result = null;
            try {
                result = handleFindResult(contactsCursor, options);
            } catch (JSONException e) {
                this.returnError(ContactsXErrorCodes.UnknownError, e.getMessage());
            }

            this._callbackContext.success(result);
        });
    }

    private ArrayList<String> getProjection(ContactsXFindOptions options) {
        ArrayList<String> projection = new ArrayList<>();
        projection.add(ContactsContract.Data.MIMETYPE);
        projection.add(ContactsContract.Contacts._ID);
        projection.add(ContactsContract.Data.CONTACT_ID);
        projection.add(ContactsContract.Data.RAW_CONTACT_ID);
        projection.add(ContactsContract.CommonDataKinds.Contactables.DATA);

        if (options.displayName) {
            projection.add(ContactsContract.Contacts.DISPLAY_NAME);
        }
        if (options.firstName) {
            projection.add(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME);
        }
        if (options.middleName) {
            projection.add(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME);
        }
        if (options.familyName) {
            projection.add(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME);
        }
        if (options.emails) {
            projection.add(ContactsContract.CommonDataKinds.Email._ID);
            projection.add(ContactsContract.CommonDataKinds.Email.DATA);
            projection.add(ContactsContract.CommonDataKinds.Email.TYPE);
            projection.add(ContactsContract.CommonDataKinds.Email.LABEL);
        }
        if (options.organizationName) {
            projection.add(ContactsContract.CommonDataKinds.Organization.COMPANY);
        }

        return projection;
    }

    private ArrayList<String> getSelectionArgs(ContactsXFindOptions options) {
        ArrayList<String> selectionArgs = new ArrayList<>();
        if (options.phoneNumbers) {
            selectionArgs.add(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE);
        }
        if (options.emails) {
            selectionArgs.add(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE);
        }
        if (options.firstName || options.middleName || options.familyName) {
            selectionArgs.add(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE);
        }
        if (options.organizationName) {
            selectionArgs.add(ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE);
        }

        return selectionArgs;
    }

    private JSONArray handleFindResult(Cursor contactsCursor, ContactsXFindOptions options) throws JSONException {
        // initialize array
        JSONArray jsContacts = new JSONArray();

        if (contactsCursor != null && contactsCursor.getCount() > 0) {
            HashMap<Object, JSONObject> contactsById = new HashMap<>();

            while (contactsCursor.moveToNext()) {
                String contactId = contactsCursor.getString(
                        contactsCursor.getColumnIndex(ContactsContract.Data.CONTACT_ID)
                );
                String rawId = contactsCursor.getString(
                        contactsCursor.getColumnIndex(ContactsContract.Data.RAW_CONTACT_ID)
                );

                JSONObject jsContact = new JSONObject();

                if (!contactsById.containsKey(contactId)) {
                    // this contact does not yet exist in HashMap,
                    // so put it to the HashMap

                    jsContact.put("id", contactId);
                    jsContact.put("rawId", rawId);
                    if (options.displayName) {
                        String displayName = contactsCursor.getString(contactsCursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME));
                        jsContact.put("displayName", displayName);
                    }
                    JSONArray jsPhoneNumbers = new JSONArray();
                    jsContact.put("phoneNumbers", jsPhoneNumbers);

                    JSONArray jsEmails = new JSONArray();
                    jsContact.put("emails", jsEmails);

                    jsContacts.put(jsContact);
                } else {
                    jsContact = contactsById.get(contactId);
                }

                String mimeType = contactsCursor.getString(
                        contactsCursor.getColumnIndex(ContactsContract.Data.MIMETYPE)
                );

                assert jsContact != null;
                switch (mimeType) {
                    case ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE:
                        JSONArray jsPhoneNumbers = jsContact.getJSONArray("phoneNumbers");
                        jsPhoneNumbers.put(phoneQuery(contactsCursor, options));
                        break;
                    case ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE:
                        JSONArray emailAddresses = jsContact.getJSONArray("emails");
                        emailAddresses.put(emailQuery(contactsCursor));
                        break;
                    case ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE:
                        if (options.organizationName) {
                            String organizationName = contactsCursor.getString(contactsCursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Organization.COMPANY));
                            jsContact.put("organizationName", organizationName);
                        }
                        break;    
                    case ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE:
                        try {
                            if (options.firstName) {
                                String firstName = contactsCursor.getString(contactsCursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME));
                                jsContact.put("firstName", firstName);
                            }
                            if (options.middleName) {
                                String middleName = contactsCursor.getString(contactsCursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME));
                                jsContact.put("middleName", middleName);
                            }
                            if (options.familyName) {
                                String familyName = contactsCursor.getString(contactsCursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME));
                                jsContact.put("familyName", familyName);
                            }
                        } catch (IllegalArgumentException ignored) {
                        }
                        break;
                }

                contactsById.put(contactId, jsContact);
            }

            contactsCursor.close();
        }

        return jsContacts;
    }

    private JSONObject phoneQuery(Cursor cursor, ContactsXFindOptions options) throws JSONException {
        JSONObject phoneNumber = new JSONObject();
        int typeCode = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE));
        String typeLabel = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LABEL));
        String type = (typeCode == ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM) ? typeLabel : getPhoneType(typeCode);
        phoneNumber.put("id", cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone._ID)));
        phoneNumber.put("value", cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)));
        phoneNumber.put("normalized", getNormalizedPhoneNumber(
                                                cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)),
                                                options));
        phoneNumber.put("type", type);
        return phoneNumber;
    }

    private JSONObject emailQuery(Cursor cursor) throws JSONException {
        JSONObject email = new JSONObject();
        int typeCode = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.TYPE));
        String typeLabel = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.LABEL));
        String type = (typeCode == ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM) ? typeLabel : getMailType(typeCode);
        email.put("id", cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email._ID)));
        email.put("value", cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.DATA)));
        email.put("type", type);
        return email;
    }

    private void pick() {
        this.cordova.getThreadPool().execute(() -> {
            Intent contactPickerIntent = new Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI);
            this.cordova.startActivityForResult(this, contactPickerIntent, REQ_CODE_PICK);
        });
    }

    private String getNormalizedPhoneNumber(String phoneNumber, ContactsXFindOptions options){

        if(options.baseCountryCode != null && phoneNumber != null){
            try {
                Phonenumber.PhoneNumber phoneNumberProto = phoneUtil.parse(phoneNumber, options.baseCountryCode);
                return phoneUtil.format(phoneNumberProto, PhoneNumberUtil.PhoneNumberFormat.E164);
            } catch (NumberParseException e) {
                return "";
            }
        }
        return "";
    }

    private JSONObject getContactById(String id) {
        Cursor c = this.cordova.getActivity().getContentResolver().query(
                ContactsContract.Data.CONTENT_URI,
                null,
                ContactsContract.Data.RAW_CONTACT_ID + " = ? ",
                new String[]{id},
                ContactsContract.Data.RAW_CONTACT_ID + " ASC");

        Map<String, Object> fields = new HashMap<>();
        fields.put("phoneNumbers", true);
        fields.put("emails", true);
        Map<String, Object> pickFields = new HashMap<>();
        pickFields.put("fields", fields);

        try {
            JSONArray contacts = handleFindResult(c, new ContactsXFindOptions(new JSONObject(pickFields)));
            if (contacts.length() == 1) {
                return contacts.getJSONObject(0);
            }
        } catch (Exception e) {
            returnError(ContactsXErrorCodes.UnknownError, e.getMessage());
        }

        return null;
    }

    /**
     * Builds a contact JSONObject from the CONTACT_ID returned by the system picker.
     * READ_CONTACTS permission is guaranteed before this is called.
     * Output structure matches iOS exactly:
     * { id, displayName, firstName, middleName, familyName, organizationName,
     *   phoneNumbers: [{id, type, value, normalized}],
     *   emails: [{id, type, value}] }
     */
    private JSONObject buildContactFromPickerUri(Uri pickerUri, String contactId) throws JSONException {
        ContentResolver cr = this.cordova.getActivity().getContentResolver();

        JSONObject result = new JSONObject();
        result.put("id", contactId);
        result.put("displayName", "");
        result.put("firstName", "");
        result.put("middleName", "");
        result.put("familyName", "");
        result.put("organizationName", "");
        result.put("phoneNumbers", new JSONArray());
        result.put("emails", new JSONArray());

        // 1. Display name — query the picker URI directly
        Cursor contactCursor = cr.query(
                pickerUri,
                new String[]{ ContactsContract.Contacts.DISPLAY_NAME },
                null, null, null);
        if (contactCursor != null) {
            if (contactCursor.moveToFirst()) {
                int idx = contactCursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME);
                if (idx >= 0) result.put("displayName", nullSafeString(contactCursor, idx));
            }
            contactCursor.close();
        }

        // 2. Structured name — requires READ_CONTACTS (now guaranteed)
        Cursor structuredCursor = cr.query(
                ContactsContract.Data.CONTENT_URI,
                new String[]{
                        ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
                        ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME,
                        ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME
                },
                ContactsContract.Data.CONTACT_ID + " = ? AND " +
                ContactsContract.Data.MIMETYPE + " = ?",
                new String[]{ contactId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE },
                null);
        if (structuredCursor != null) {
            if (structuredCursor.moveToFirst()) {
                int givenIdx  = structuredCursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME);
                int middleIdx = structuredCursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME);
                int familyIdx = structuredCursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME);
                if (givenIdx  >= 0) result.put("firstName",  nullSafeString(structuredCursor, givenIdx));
                if (middleIdx >= 0) result.put("middleName", nullSafeString(structuredCursor, middleIdx));
                if (familyIdx >= 0) result.put("familyName", nullSafeString(structuredCursor, familyIdx));
            }
            structuredCursor.close();
        }

        // 3. Organisation
        Cursor orgCursor = cr.query(
                ContactsContract.Data.CONTENT_URI,
                new String[]{ ContactsContract.CommonDataKinds.Organization.COMPANY },
                ContactsContract.Data.CONTACT_ID + " = ? AND " +
                ContactsContract.Data.MIMETYPE + " = ?",
                new String[]{ contactId, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE },
                null);
        if (orgCursor != null) {
            if (orgCursor.moveToFirst()) {
                int orgIdx = orgCursor.getColumnIndex(ContactsContract.CommonDataKinds.Organization.COMPANY);
                if (orgIdx >= 0) result.put("organizationName", nullSafeString(orgCursor, orgIdx));
            }
            orgCursor.close();
        }

        // 4. Phone numbers
        Cursor phoneCursor = cr.query(
                ContactsContract.Data.CONTENT_URI,
                new String[]{
                        ContactsContract.CommonDataKinds.Phone._ID,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.LABEL
                },
                ContactsContract.Data.CONTACT_ID + " = ? AND " +
                ContactsContract.Data.MIMETYPE + " = ?",
                new String[]{ contactId, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE },
                null);
        if (phoneCursor != null) {
            JSONArray phones = new JSONArray();
            ContactsXFindOptions emptyOptions = new ContactsXFindOptions(null);
            while (phoneCursor.moveToNext()) {
                phones.put(phoneQuery(phoneCursor, emptyOptions));
            }
            phoneCursor.close();
            result.put("phoneNumbers", phones);
        }

        // 5. Emails
        Cursor emailCursor = cr.query(
                ContactsContract.Data.CONTENT_URI,
                new String[]{
                        ContactsContract.CommonDataKinds.Email._ID,
                        ContactsContract.CommonDataKinds.Email.DATA,
                        ContactsContract.CommonDataKinds.Email.TYPE,
                        ContactsContract.CommonDataKinds.Email.LABEL
                },
                ContactsContract.Data.CONTACT_ID + " = ? AND " +
                ContactsContract.Data.MIMETYPE + " = ?",
                new String[]{ contactId, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE },
                null);
        if (emailCursor != null) {
            JSONArray emails = new JSONArray();
            while (emailCursor.moveToNext()) {
                emails.put(emailQuery(emailCursor));
            }
            emailCursor.close();
            result.put("emails", emails);
        }

        LOG.d(LOG_TAG, "buildContactFromPickerUri result=" + result.toString());
        return result;
    }

    /**
     * Builds a contact JSONObject from a CONTACT_ID, matching the iOS output structure exactly:
     * { id, displayName, firstName, middleName, familyName, organizationName,
     *   phoneNumbers: [{id, type, value, normalized}],
     *   emails: [{id, type, value}] }
     */
    private JSONObject buildContactFromId(String contactId) throws JSONException {
        ContentResolver cr = this.cordova.getActivity().getContentResolver();

        JSONObject result = new JSONObject();
        result.put("id", contactId);
        result.put("phoneNumbers", new JSONArray());
        result.put("emails", new JSONArray());

        // 1. Fetch display name + structured name from Contacts table
        Cursor nameCursor = cr.query(
                ContactsContract.Contacts.CONTENT_URI,
                new String[]{
                        ContactsContract.Contacts.DISPLAY_NAME
                },
                ContactsContract.Contacts._ID + " = ?",
                new String[]{contactId}, null);
        if (nameCursor != null) {
            if (nameCursor.moveToFirst()) {
                int displayNameIdx = nameCursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME);
                if (displayNameIdx >= 0) result.put("displayName", nameCursor.getString(displayNameIdx));
            }
            nameCursor.close();
        }

        // 2. Fetch structured name fields from Data table
        Cursor structuredCursor = cr.query(
                ContactsContract.Data.CONTENT_URI,
                new String[]{
                        ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
                        ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME,
                        ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME
                },
                ContactsContract.Data.CONTACT_ID + " = ? AND " +
                ContactsContract.Data.MIMETYPE + " = ?",
                new String[]{contactId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE},
                null);
        if (structuredCursor != null) {
            if (structuredCursor.moveToFirst()) {
                int givenIdx  = structuredCursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME);
                int middleIdx = structuredCursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME);
                int familyIdx = structuredCursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME);
                if (givenIdx  >= 0) result.put("firstName",  nullSafeString(structuredCursor, givenIdx));
                if (middleIdx >= 0) result.put("middleName", nullSafeString(structuredCursor, middleIdx));
                if (familyIdx >= 0) result.put("familyName", nullSafeString(structuredCursor, familyIdx));
            }
            structuredCursor.close();
        }

        // 3. Fetch organisation from Data table
        Cursor orgCursor = cr.query(
                ContactsContract.Data.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Organization.COMPANY},
                ContactsContract.Data.CONTACT_ID + " = ? AND " +
                ContactsContract.Data.MIMETYPE + " = ?",
                new String[]{contactId, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE},
                null);
        if (orgCursor != null) {
            if (orgCursor.moveToFirst()) {
                int orgIdx = orgCursor.getColumnIndex(ContactsContract.CommonDataKinds.Organization.COMPANY);
                if (orgIdx >= 0) result.put("organizationName", nullSafeString(orgCursor, orgIdx));
            }
            orgCursor.close();
        }

        // 4. Fetch phone numbers from Data table
        Cursor phoneCursor = cr.query(
                ContactsContract.Data.CONTENT_URI,
                new String[]{
                        ContactsContract.CommonDataKinds.Phone._ID,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.LABEL
                },
                ContactsContract.Data.CONTACT_ID + " = ? AND " +
                ContactsContract.Data.MIMETYPE + " = ?",
                new String[]{contactId, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE},
                null);
        if (phoneCursor != null) {
            JSONArray phones = new JSONArray();
            ContactsXFindOptions emptyOptions = new ContactsXFindOptions(null);
            while (phoneCursor.moveToNext()) {
                phones.put(phoneQuery(phoneCursor, emptyOptions));
            }
            phoneCursor.close();
            result.put("phoneNumbers", phones);
        }

        // 5. Fetch emails from Data table
        Cursor emailCursor = cr.query(
                ContactsContract.Data.CONTENT_URI,
                new String[]{
                        ContactsContract.CommonDataKinds.Email._ID,
                        ContactsContract.CommonDataKinds.Email.DATA,
                        ContactsContract.CommonDataKinds.Email.TYPE,
                        ContactsContract.CommonDataKinds.Email.LABEL
                },
                ContactsContract.Data.CONTACT_ID + " = ? AND " +
                ContactsContract.Data.MIMETYPE + " = ?",
                new String[]{contactId, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE},
                null);
        if (emailCursor != null) {
            JSONArray emails = new JSONArray();
            while (emailCursor.moveToNext()) {
                emails.put(emailQuery(emailCursor));
            }
            emailCursor.close();
            result.put("emails", emails);
        }

        LOG.d(LOG_TAG, "buildContactFromId result=" + result.toString());
        return result;
    }

    private String nullSafeString(Cursor cursor, int columnIndex) {
        String val = cursor.getString(columnIndex);
        return val != null ? val : "";
    }

    private void save(JSONArray args) throws JSONException {
        final JSONObject contact = args.getJSONObject(0);
        this.cordova.getThreadPool().execute(() -> {
            JSONObject res = null;
            String id = performSave(contact);
            if (id != null) {
                res = getContactById(id);
            }
            if (res != null) {
                _callbackContext.success(res);
            } else {
                returnError(ContactsXErrorCodes.UnknownError);
            }
        });
    }

    private String performSave(JSONObject contact) {
        AccountManager mgr = AccountManager.get(this.cordova.getActivity());
        Account[] accounts = mgr.getAccounts();
        String accountName = null;
        String accountType = null;

        if (accounts.length == 1) {
            accountName = accounts[0].name;
            accountType = accounts[0].type;
        } else if (accounts.length > 1) {
            for (Account a : accounts) {
                if (a.type.contains("eas") && a.name.matches(EMAIL_REGEXP)) /*Exchange ActiveSync*/ {
                    accountName = a.name;
                    accountType = a.type;
                    break;
                }
            }
            if (accountName == null) {
                for (Account a : accounts) {
                    if (a.type.contains("com.google") && a.name.matches(EMAIL_REGEXP)) /*Google sync provider*/ {
                        accountName = a.name;
                        accountType = a.type;
                        break;
                    }
                }
            }
            if (accountName == null) {
                for (Account a : accounts) {
                    if (a.name.matches(EMAIL_REGEXP)) /*Last resort, just look for an email address...*/ {
                        accountName = a.name;
                        accountType = a.type;
                        break;
                    }
                }
            }
        }

        String id = getJsonString(contact, "id");
        if (id == null) {
            // Create new contact
            return newContact(contact, accountType, accountName);
        } else {
            // Modify existing contact
            return modifyContact(id, contact, accountType, accountName);
        }
    }

    private String newContact(JSONObject contact, String accountType, String accountName) {
        // Create a list of attributes to add to the contact database
        ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();

        //Add contact type
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, accountType)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, accountName)
                .build());

        // Add name
        String displayName = getJsonString(contact, "displayName");
        String firstName = getJsonString(contact, "firstName");
        String middleName = getJsonString(contact, "middleName");
        String familyName = getJsonString(contact, "familyName");
        if (displayName != null || firstName != null || middleName != null || familyName != null) {
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, familyName)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME, middleName)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, firstName)
                    .build());
        } else {
            LOG.d(LOG_TAG, "All \"name\" properties are empty");
        }
        
        // Add organizationName
        String organizationName = getJsonString(contact, "organizationName");
        if(organizationName != null){
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, organizationName)
                    .build());
        }

        //Add phone numbers
        JSONArray phones;
        try {
            phones = contact.getJSONArray("phoneNumbers");
            for (int i = 0; i < phones.length(); i++) {
                if (!phones.isNull(i)) {
                    JSONObject phone = (JSONObject) phones.get(i);
                    ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, getJsonString(phone, "value"))
                            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, getPhoneType(getJsonString(phone, "type")))
                            .withValue(ContactsContract.CommonDataKinds.Phone.LABEL, getJsonString(phone, "type"))
                            .build());
                }
            }
        } catch (JSONException e) {
            LOG.d(LOG_TAG, "Could not get phone numbers");
        }

        // Add emails
        JSONArray emails;
        try {
            emails = contact.getJSONArray("emails");
            for (int i = 0; i < emails.length(); i++) {
                JSONObject email = (JSONObject) emails.get(i);
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Email.DATA, getJsonString(email, "value"))
                        .withValue(ContactsContract.CommonDataKinds.Email.TYPE, getMailType(getJsonString(email, "type")))
                        .withValue(ContactsContract.CommonDataKinds.Email.LABEL, getJsonString(email, "type"))
                        .build());
            }
        } catch (JSONException e) {
            LOG.d(LOG_TAG, "Could not get emails");
        }

        String newId = null;
        //Add contact
        try {
            ContentProviderResult[] cpResults = this.cordova.getActivity().getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
            if (cpResults.length > 0) {
                newId = cpResults[0].uri.getLastPathSegment();
            }
        } catch (RemoteException | OperationApplicationException e) {
            LOG.e(LOG_TAG, e.getMessage(), e);
        }
        return newId;
    }

    private String modifyContact(String id, JSONObject contact, String accountType, String accountName) {
        // Get the RAW_CONTACT_ID which is needed to insert new values in an already existing contact.
        // But not needed to update existing values.
        String rawId = getJsonString(contact, "rawId");

        // Create a list of attributes to add to the contact database
        ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();

        //Add contact type
        ops.add(ContentProviderOperation.newUpdate(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, accountType)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, accountName)
                .build());

        // Modify name
        String displayName = getJsonString(contact, "displayName");
        String firstName = getJsonString(contact, "firstName");
        String middleName = getJsonString(contact, "middleName");
        String familyName = getJsonString(contact, "familyName");
        if (displayName != null || firstName != null || middleName != null || familyName != null) {
            ContentProviderOperation.Builder builder = ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                    .withSelection(ContactsContract.Data.CONTACT_ID + "=? AND " +
                                    ContactsContract.Data.MIMETYPE + "=?",
                            new String[]{id, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE});

            if (displayName != null) {
                builder.withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName);
            }

            if (familyName != null) {
                builder.withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, familyName);
            }
            if (middleName != null) {
                builder.withValue(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME, middleName);
            }
            if (firstName != null) {
                builder.withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, firstName);
            }

            ops.add(builder.build());
        }

        // Modify organizationName
        String organizationName = getJsonString(contact, "organizationName");
        if(organizationName != null){
            try {
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, organizationName)
                        .build());
            } catch (Error error) {
                LOG.d(LOG_TAG, "Could not set organizationName" + error);
            }
        }

        // Modify phone numbers
        JSONArray phones;
        try {
            phones = contact.getJSONArray("phoneNumbers");
            // Delete all the phones
            if (phones.length() == 0) {
                ops.add(ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                        .withSelection(ContactsContract.Data.RAW_CONTACT_ID + "=? AND " +
                                        ContactsContract.Data.MIMETYPE + "=?",
                                new String[]{"" + rawId, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE})
                        .build());
            }
            // Modify or add a phone
            else {
                for (int i = 0; i < phones.length(); i++) {
                    JSONObject phone = (JSONObject) phones.get(i);
                    String phoneId = getJsonString(phone, "id");
                    // This is a new phone so do a DB insert
                    if (phoneId == null) {
                        ContentValues contentValues = new ContentValues();
                        contentValues.put(ContactsContract.Data.RAW_CONTACT_ID, rawId);
                        contentValues.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE);
                        contentValues.put(ContactsContract.CommonDataKinds.Phone.NUMBER, getJsonString(phone, "value"));
                        contentValues.put(ContactsContract.CommonDataKinds.Phone.TYPE, getPhoneType(getJsonString(phone, "type")));
                        contentValues.put(ContactsContract.CommonDataKinds.Phone.LABEL, getJsonString(phone, "type"));

                        ops.add(ContentProviderOperation.newInsert(
                                ContactsContract.Data.CONTENT_URI).withValues(contentValues).build());
                    }
                    // This is an existing phone so do a DB update
                    else {
                        ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                                .withSelection(ContactsContract.CommonDataKinds.Phone._ID + "=? AND " +
                                                ContactsContract.Data.MIMETYPE + "=?",
                                        new String[]{phoneId, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE})
                                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, getJsonString(phone, "value"))
                                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, getPhoneType(getJsonString(phone, "type")))
                                .withValue(ContactsContract.CommonDataKinds.Phone.LABEL, getJsonString(phone, "type"))
                                .build());
                    }
                }
            }
        } catch (JSONException e) {
            LOG.d(LOG_TAG, "Could not get phone numbers");
        }

        // Modify emails
        JSONArray emails;
        try {
            emails = contact.getJSONArray("emails");
            // Delete all the emails
            if (emails.length() == 0) {
                ops.add(ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                        .withSelection(ContactsContract.Data.RAW_CONTACT_ID + "=? AND " +
                                        ContactsContract.Data.MIMETYPE + "=?",
                                new String[]{"" + rawId, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE})
                        .build());
            }
            // Modify or add a email
            else {
                for (int i = 0; i < emails.length(); i++) {
                    JSONObject email = (JSONObject) emails.get(i);
                    String emailId = getJsonString(email, "id");
                    // This is a new email so do a DB insert
                    if (emailId == null) {
                        ContentValues contentValues = new ContentValues();
                        contentValues.put(ContactsContract.Data.RAW_CONTACT_ID, rawId);
                        contentValues.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE);
                        contentValues.put(ContactsContract.CommonDataKinds.Email.DATA, getJsonString(email, "value"));
                        contentValues.put(ContactsContract.CommonDataKinds.Email.TYPE, getMailType(getJsonString(email, "type")));
                        contentValues.put(ContactsContract.CommonDataKinds.Email.LABEL, getJsonString(email, "type"));

                        ops.add(ContentProviderOperation.newInsert(
                                ContactsContract.Data.CONTENT_URI).withValues(contentValues).build());
                    }
                    // This is an existing email so do a DB update
                    else {
                        String emailValue = getJsonString(email, "value");
                        if (!emailValue.isEmpty()) {
                            ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                                    .withSelection(ContactsContract.CommonDataKinds.Email._ID + "=? AND " +
                                                    ContactsContract.Data.MIMETYPE + "=?",
                                            new String[]{emailId, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE})
                                    .withValue(ContactsContract.CommonDataKinds.Email.DATA, getJsonString(email, "value"))
                                    .withValue(ContactsContract.CommonDataKinds.Email.TYPE, getMailType(getJsonString(email, "type")))
                                    .withValue(ContactsContract.CommonDataKinds.Email.LABEL, getJsonString(email, "type"))
                                    .build());
                        } else {
                            ops.add(ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                                    .withSelection(ContactsContract.CommonDataKinds.Email._ID + "=? AND " +
                                                    ContactsContract.Data.MIMETYPE + "=?",
                                            new String[]{emailId, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE})
                                    .build());
                        }
                    }
                }
            }
        } catch (JSONException e) {
            LOG.d(LOG_TAG, "Could not get emails");
        }

        boolean retVal = true;
        //Modify contact
        try {
            this.cordova.getActivity().getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
        } catch (RemoteException | OperationApplicationException e) {
            LOG.e(LOG_TAG, e.getMessage(), e);
            retVal = false;
        }

        // if the save was a success return the contact ID
        if (retVal) {
            return rawId;
        } else {
            return null;
        }
    }

    private void delete(JSONArray args) throws JSONException {
        final String contactId = args.getString(0);
        this.cordova.getThreadPool().execute(() -> {
            if (performDelete(contactId)) {
                _callbackContext.success();
            } else {
                returnError(ContactsXErrorCodes.UnknownError);
            }
        });
    }

    private boolean performDelete(String id) {
        int result = 0;
        Cursor cursor = this.cordova.getActivity().getContentResolver().query(ContactsContract.Contacts.CONTENT_URI,
                null,
                ContactsContract.Contacts._ID + " = ?",
                new String[] { id }, null);

        if (cursor.getCount() == 1) {
            cursor.moveToFirst();
            String lookupKey = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY));
            Uri uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey);
            result = this.cordova.getActivity().getContentResolver().delete(uri, null, null);
        } else {
            LOG.d(LOG_TAG, "Could not find contact with ID");
        }

        cursor.close();

        return result > 0;
    }

    private void hasPermission() throws JSONException {
        JSONObject response = new JSONObject();
        response.put("read", PermissionHelper.hasPermission(this, READ));
        response.put("write", PermissionHelper.hasPermission(this, WRITE));
        if (this._callbackContext != null) {
            this._callbackContext.success(response);
        }
    }

    private void requestPermission(boolean write) {
        PermissionHelper.requestPermission(this, REQ_CODE_PERMISSIONS, write ? WRITE : READ);
    }

    private void returnError(ContactsXErrorCodes errorCode) {
        returnError(errorCode, null);
    }

    private void returnError(ContactsXErrorCodes errorCode, String message) {
        if (_callbackContext != null) {
            // Send as plain string so JS can display it directly without [object Object]
            String errorMessage = "code:" + errorCode.value + "|message:" + (message == null ? "" : message);
            _callbackContext.error(errorMessage);
            _callbackContext = null;
        }
    }

    // Helper

    private String getJsonString(JSONObject obj, String property) {
        String value = null;
        try {
            if (obj != null) {
                value = obj.getString(property);
            }
        } catch (JSONException e) {
            LOG.d(LOG_TAG, "Could not get = " + e.getMessage());
        }
        return value;
    }

    /**
     * Converts a string from the W3C Contact API to it's Android int value.
     */
    private int getMailType(String string) {
        int type = ContactsContract.CommonDataKinds.Email.TYPE_OTHER;
        if (string != null) {

            String lowerType = string.toLowerCase(Locale.getDefault());

            switch (lowerType) {
                case "home":
                    return ContactsContract.CommonDataKinds.Email.TYPE_HOME;
                case "work":
                    return ContactsContract.CommonDataKinds.Email.TYPE_WORK;
                case "other":
                    return ContactsContract.CommonDataKinds.Email.TYPE_OTHER;
                case "mobile":
                    return ContactsContract.CommonDataKinds.Email.TYPE_MOBILE;
            }
        }
        return type;
    }

    /**
     * getPhoneType converts an Android mail type into a string
     */
    private String getMailType(int type) {
        String stringType;
        switch (type) {
            case ContactsContract.CommonDataKinds.Email.TYPE_HOME:
                stringType = "home";
                break;
            case ContactsContract.CommonDataKinds.Email.TYPE_WORK:
                stringType = "work";
                break;
            case ContactsContract.CommonDataKinds.Email.TYPE_MOBILE:
                stringType = "mobile";
                break;
            case ContactsContract.CommonDataKinds.Email.TYPE_OTHER:
            default:
                stringType = "other";
                break;
        }
        return stringType;
    }

    /**
     * getPhoneType converts an Android phone type into a string
     *
     * @return phone type as string.
     */
    private String getPhoneType(int type) {
        String stringType;

        switch (type) {
            case ContactsContract.CommonDataKinds.Phone.TYPE_HOME:
                stringType = "home";
                break;
            case ContactsContract.CommonDataKinds.Phone.TYPE_WORK:
                stringType = "work";
                break;
            case ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE:
                stringType = "mobile";
                break;
            case ContactsContract.CommonDataKinds.Phone.TYPE_OTHER:
            default:
                stringType = "other";
                break;
        }
        return stringType;
    }

    /**
     * Converts a string from the W3C Contact API to it's Android int value.
     *
     * @return Android int value
     */
    private int getPhoneType(String string) {

        int type = ContactsContract.CommonDataKinds.Phone.TYPE_OTHER;

        if (string != null) {
            String lowerType = string.toLowerCase(Locale.getDefault());

            switch (lowerType) {
                case "home":
                    return ContactsContract.CommonDataKinds.Phone.TYPE_HOME;
                case "work":
                    return ContactsContract.CommonDataKinds.Phone.TYPE_WORK;
                case "mobile":
                    return ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE;
            }
        }
        return type;
    }
}
