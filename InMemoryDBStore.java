
import java.util.HashMap;
import java.util.Map;

public class InMemoryDBStore {
    private static Map<String,Map<String,String>> store; //Key ->[Field->Value]

    public InMemoryDBStore() {
        this.store=new HashMap<>();
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

    public static void main(String[] args) {
        InMemoryDBStore db = new InMemoryDBStore();

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

    }
}
