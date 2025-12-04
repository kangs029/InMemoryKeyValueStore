
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class InMemoryDBStore {
    private final Map<String,Map<String,String>> store; //Key ->[Field->Value] - level 1&level2
    
    // TreeMap stores timestamp -> FieldValue to enable efficient time-based queries
    private final Map<String, Map<String, TreeMap<Integer, FieldValue>>> fieldHistory; //key -> (field -> TreeMap<timestamp, FieldValue>)

    // Helper class to store field value with optional TTL
    private static class FieldValue {
        final String value;
        final Integer ttlExpiry; // null if no TTL, otherwise timestamp + ttl

        FieldValue(String value, Integer ttlExpiry) {
            this.value = value;
            this.ttlExpiry = ttlExpiry;
        }

        boolean isExpiredAt(int timestamp) {
            return ttlExpiry != null && timestamp > ttlExpiry;
        }
    }

    public InMemoryDBStore() {
        this.store=new HashMap<>();
        this.fieldHistory=new HashMap<>();
    }

    public void Set(String key,String field,String value){
        store.computeIfAbsent(key, k->new HashMap<>()).put(field,value);
    }

    public String Get(String key, String field) {
        if(!store.containsKey(key)) return null;
        if (!store.get(key).containsKey(field)) return null;
        return store.get(key).get(field);
    }

    public boolean delete(String key, String field) {
        if(!store.containsKey(key)) return false;
        if (!store.get(key).containsKey(field)) return false;

        Map<String, String> record = store.get(key);
        record.remove(field);

        return true;
    }
    /*
      -> Returns list of strings in format "field(value)", sorted lexicographically by field.
      -> If key doesn't exist, returns an empty list.
     */
    public List<String> scan(String key) {
        if(!store.containsKey(key)) {
            return new ArrayList<>();
        }
        Map<String, String> record = store.get(key);
        List<String> fields = new ArrayList<>(record.keySet());
        Collections.sort(fields);

        List<String> res = new ArrayList<>();
        for (String field : fields) {
            res.add(String.format("%s(%s)", field, record.get(field)));
        }
        return res;
    }

    /**
      ->Scan fields that start with the given prefix for the given key.
      ->Returns same as format like scan method
     */
    public List<String> scanByPrefix(String key, String prefix) {
        if(!store.containsKey(key)) {
            return new ArrayList<>();
        }

        Map<String, String> record = store.get(key);
        List<String> matchedFields = new ArrayList<>();
        for (String field : record.keySet()) {
            if (field.startsWith(prefix)) {
                matchedFields.add(field);
            }
        }
        Collections.sort(matchedFields);

        List<String> res = new ArrayList<>();
        for (String field : matchedFields) {
            String value = record.get(field);
            res.add(String.format("%s(%s)", field, value));
        }
        return res;
    }

     /**
      Helper method to get field value at a specific timestamp
      Returns the most recent value set before or at the given timestamp, considering TTL
     */
    private String getFieldValueAt(String key, String field, int timestamp) {

        if(!fieldHistory.containsKey(key))return null;
        if(!fieldHistory.get(key).containsKey(field)) return null;

        TreeMap<Integer, FieldValue> fieldTimeline = fieldHistory.get(key).get(field);
        if (fieldTimeline == null || fieldTimeline.isEmpty()) {
            return null;
        }

        // Find the most recent entry <= timestamp
        Map.Entry<Integer, FieldValue> entry = fieldTimeline.floorEntry(timestamp);
        if (entry == null) {
            return null;
        }

        FieldValue fv = entry.getValue();
        if (fv.isExpiredAt(timestamp)) {
            return null;
        }
        return fv.value;
    }

    /**
     * Level 3: SetAt operation
     * Inserts a field-value pair or updates the value of the field in the record associated with key at the given timestamp.
     * Returns a list of strings representing the fields after the operation (similar to Scan format).
     * 
     * @param key The key of the record
     * @param field The field name
     * @param value The value to set
     * @param timestamp The timestamp of the operation
     */
    public void setAt(String key, String field, String value, int timestamp) {
        if (key == null || field == null || value == null) {
            return;
        }
        // Update old database
        Set(key,field, value);

        // Update history db
        Map<String, TreeMap<Integer, FieldValue>> recordHistory = fieldHistory.computeIfAbsent(key, k -> new HashMap<>());
        TreeMap<Integer, FieldValue> fieldTimeline = recordHistory.computeIfAbsent(field, f -> new TreeMap<>());
        fieldTimeline.put(timestamp, new FieldValue(value, null)); // No TTL
    }
    /**
     * Level 3: SetAtWithTtl operation
     * Inserts a field-value pair or updates the value of the field in the record associated with key.
     * Also sets its Time-to-Live starting at timestamp to be ttl.
     * The ttl is the amount of time that this field-value pair should exist in the database,
     * meaning it will be available during the interval: [timestamp, timestamp + ttl]
     * 
     * @param key The key of the record
     * @param field The field name
     * @param value The value to set
     * @param timestamp The timestamp of the operation
     * @param ttl Time-to-live duration
     */
    public void setAtWithTtl(String key, String field, String value, int timestamp, int ttl) {
        if (key == null || field == null || value == null || ttl<0) {
            return;
        }

        // Update old database
        Set(key,field, value);

        // Update history db
        Map<String, TreeMap<Integer, FieldValue>> recordHistory = fieldHistory.computeIfAbsent(key, k -> new HashMap<>());
        TreeMap<Integer, FieldValue> fieldTimeline = recordHistory.computeIfAbsent(field, f -> new TreeMap<>());
        fieldTimeline.put(timestamp, new FieldValue(value, timestamp + ttl));
    }

    /**
     * Level 3: DeleteAt operation
     * The same as Delete, but with timestamp of the operation specified.
     * 
     * @param key The key of the record
     * @param field The field name to delete
     * @param timestamp The timestamp of the operation
     * @return true if field was deleted, false if key doesn't exist
     */
    public boolean deleteAt(String key, String field, int timestamp) {
        String val=getFieldValueAt(key,field,timestamp);
        if(val==null) return false;

        // delete field in old database
        delete(key,field);
        
        //delete in new db
        fieldHistory.get(key).get(field).put(timestamp, new FieldValue(null, null));
        return true;
    }

    /**
     * Level 3: GetAt operation
     * The same as Get, but with timestamp of the operation specified.
     * 
     * @param key The key of the record
     * @param field The field name
     * @param timestamp The timestamp to query at
     * @return The value of the field at the given timestamp, or null if not found/expired
     */
    public String getAt(String key, String field, int timestamp) {
        return getFieldValueAt(key, field, timestamp);
    }

    /**
     * Level 3: ScanAt operation
     * The same as Scan, but with the timestamp of the operation specified.
     * 
     * @param key The key of the record
     * @param timestamp The timestamp to query at
     * @return List of strings in format "field(value)", sorted lexicographically
     */
    public List<String> scanAt(String key, int timestamp) {
        if (!fieldHistory.containsKey(key)) {
            return new ArrayList<>();
        }

        // Collect all fields that exist at this timestamp
        Map<String, String> fieldsAtTimestamp = new HashMap<>();

        for (Map.Entry<String, TreeMap<Integer, FieldValue>> entry : fieldHistory.get(key).entrySet()) {//entry->field :[timestamp:Record]
            String field = entry.getKey();
            TreeMap<Integer, FieldValue> fieldTimeline = entry.getValue();

            Map.Entry<Integer, FieldValue> latestEntry = fieldTimeline.floorEntry(timestamp);
            if (latestEntry != null) {
                FieldValue fv = latestEntry.getValue();
                // Check if expired
                if (!fv.isExpiredAt(timestamp) && fv.value != null) {
                    fieldsAtTimestamp.put(field, fv.value);
                }
            }
        }

        // Format and sort
        List<String> result = new ArrayList<>();
        List<String> fields = new ArrayList<>(fieldsAtTimestamp.keySet());
        Collections.sort(fields);

        for (String field : fields) {
            String value = fieldsAtTimestamp.get(field);
            result.add(field + "(" + value + ")");
        }

        return result;
    }
    /**
     * Level 3: ScanPrefixAt operation
     * The same as ScanByPrefix, but with the timestamp of the operation specified.
     * 
     * @param key The key of the record
     * @param prefix The prefix to match
     * @param timestamp The timestamp to query at
     * @return List of strings in format "field(value)", sorted lexicographically
     */
    public List<String> scanPrefixAt(String key, String prefix, int timestamp) {
        if (!fieldHistory.containsKey(key)) {
            return new ArrayList<>();
        }

        // Collect fields that start with prefix and exist at this timestamp
        Map<String, String> matchingFields = new HashMap<>();

        for (Map.Entry<String, TreeMap<Integer, FieldValue>> entry : fieldHistory.get(key).entrySet()) {
            String field = entry.getKey();
            if (!field.startsWith(prefix)) {
                continue;
            }

            TreeMap<Integer, FieldValue> fieldTimeline = entry.getValue();
            Map.Entry<Integer, FieldValue> latestEntry = fieldTimeline.floorEntry(timestamp);
            if (latestEntry != null) {
                FieldValue fv = latestEntry.getValue();
                // Check if expired
                if (!fv.isExpiredAt(timestamp) && fv.value != null) {
                    matchingFields.put(field, fv.value);
                }
            }
        }

        // Format and sort
        List<String> result = new ArrayList<>();
        List<String> fields = new ArrayList<>(matchingFields.keySet());
        Collections.sort(fields);

        for (String field : fields) {
            String value = matchingFields.get(field);
            result.add(field + "(" + value + ")");
        }

        return result;
    }

    public static void main(String[] args) {
        InMemoryDBStore db = new InMemoryDBStore();

         System.out.println("Level 1 Test\n");
        db.Set("user:1", "name", "Alice");
        db.Set("user:1", "email", "alice@example.com");

        System.out.println(db.Get("user:1", "name"));//Alice
        System.out.println(db.Get("user:2", "name"));//null -> key not present
        System.out.println(db.Get("user:1", "unknown"));//null ->field not present

        db.Set("user:1", "name", "Alicia");
        System.out.println(db.Get("user:1", "name")); //Alicia -> name updated

        System.out.println(db.delete("user:1", "email"));//True
        System.out.println(db.delete("user:1", "email")); // false ->already deleted
        System.out.println(db.delete("unknown", "field"));//false -> key not present

        db.delete("user:1", "name"); // last field deleted → key removed
        System.out.println(db.Get("user:1", "name")); // null ->since key also deleted after all records removed

        System.out.println("\nLevel 2 Test\n");
        db.Set("user:1", "name", "Alice");
        db.Set("user:1", "email", "alice@example.com");
        db.Set("user:1", "age", "30");
        db.Set("user:1", "address", "Wonderland");

        // Scan should be sorted by field names: address, age, email, name
        List<String> scanAll = db.scan("user:1");
        System.out.println("scan(user:1) -> " + scanAll);
        // Expected: [address(Wonderland), age(30), email(alice@example.com), name(Alice)]

        // Scan by prefix "a" should include address and age (sorted: address, age)
        List<String> scanPrefixA = db.scanByPrefix("user:1", "a");
        System.out.println("scanByPrefix(user:1, \"a\") -> " + scanPrefixA);
        // Expected: [address(Wonderland), age(30)]

        // Non-existing key returns empty lists
        System.out.println("scan(nonexistent) -> " + db.scan("nope"));
        System.out.println("scanByPrefix(nonexistent, \"x\") -> " + db.scanByPrefix("nope", "x"));

        System.out.println("\nLevel 3 Test\n");
        InMemoryDBStore db3 = new InMemoryDBStore();

        // Test SetAt
        db3.setAt("user1", "name", "Alice", 10);
        db3.setAt("user1", "email", "alice@example.com", 15);
        db3.setAt("user1", "age", "30", 20);

        // Test GetAt
        System.out.println("getAt(user1, name, 10): " + db3.getAt("user1", "name", 10)); // Should print: Alice
        System.out.println("getAt(user1, name, 5): " + db3.getAt("user1", "name", 5)); // Should print: null (before creation)
        System.out.println("getAt(user1, email, 20): " + db3.getAt("user1", "email", 20)); // Should print: alice@example.com

        // Test ScanAt
        List<String> scanAtResult = db3.scanAt("user1", 25);
        System.out.println("scanAt(user1, 25): " + scanAtResult); // Should print: [age(30), email(alice@example.com), name(Alice)]

        // Test SetAtWithTtl
        db3.setAtWithTtl("user1", "temp", "temporary", 30, 10); // Expires at 40
        System.out.println("getAt(user1, temp, 35): " + db3.getAt("user1", "temp", 35)); // Should print: temporary
        System.out.println("getAt(user1, temp, 45): " + db3.getAt("user1", "temp", 45)); // Should print: null (expired)

        // Test ScanPrefixAt
        List<String> scanPrefixResult = db3.scanPrefixAt("user1", "a", 25);
        System.out.println("scanPrefixAt(user1, 'a', 25): " + scanPrefixResult);// Should print: [age(30)] (only fields starting with 'a')

        // Test DeleteAt
        System.out.println("deleteAt(user1, email, 50): " + db3.deleteAt("user1", "email", 50)); // Should print: true
        System.out.println("deleteAt(user1, email, 50): " + db3.deleteAt("user1", "email", 55)); // Should print: false ->since already deleted
        System.out.println("deleteAt(user1, email, 50): " + db3.deleteAt("user1", "temp",45)); // Should print: false -> since expired
        System.out.println("getAt(user1, email, 55): " + db3.getAt("user1", "email", 55)); // Should print: null (deleted)
        System.out.println("getAt(user1, email, 45): " + db3.getAt("user1", "email", 45)); // Should print: alice@example.com (before deletion)

    }
}
