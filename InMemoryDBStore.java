
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InMemoryDBStore {
    private static Map<String,Map<String,String>> store; //Key ->[Field->Value]

    public InMemoryDBStore() {
        store=new HashMap<>();
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

        if (record.isEmpty()) {
            store.remove(key);
        }
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

    }
}
