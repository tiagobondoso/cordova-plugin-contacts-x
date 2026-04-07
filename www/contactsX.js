var exec = require('cordova/exec');

var contactsX = {
  ErrorCodes: {
    UnsupportedAction: 1,
    WrongJsonObject: 2,
    PermissionDenied: 3,
    UnknownError: 10
  },

  find: function (success, error, options) {
    exec(success, error, 'ContactsX', 'find', [options]);
  },

  pick: function (success, error) {
    exec(function(result) {
      // Android returns the contact as a JSON string; iOS returns a parsed object.
      // Normalise to always deliver a parsed object to the caller.
      if (typeof result === 'string') {
        try { result = JSON.parse(result); } catch(e) { /* already an object */ }
      }
      success(result);
    }, error, 'ContactsX', 'pick', []);
  },
  
  save: function (contact, success, error) {
    exec(success, error, 'ContactsX', 'save', [contact]);
  },

  delete: function (id, success, error) {
    exec(success, error, 'ContactsX', 'delete', [id]);
  },

  hasPermission: function (success, error) {
    exec(success, error, 'ContactsX', 'hasPermission', []);
  },

  requestPermission: function (success, error) {
    exec(success, error, 'ContactsX', 'requestPermission', [false]);
  },

  requestWritePermission: function (success, error) {
    exec(success, error, 'ContactsX', 'requestPermission', [true]);
  }
}

module.exports = contactsX;
