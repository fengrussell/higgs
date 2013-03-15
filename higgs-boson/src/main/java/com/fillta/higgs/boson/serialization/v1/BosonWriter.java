package com.fillta.higgs.boson.serialization.v1;

import com.fillta.higgs.boson.BosonMessage;
import com.fillta.higgs.boson.serialization.BosonProperty;
import com.fillta.higgs.reflect.ReflectionUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.fillta.higgs.boson.BosonType.ARRAY;
import static com.fillta.higgs.boson.BosonType.BOOLEAN;
import static com.fillta.higgs.boson.BosonType.BYTE;
import static com.fillta.higgs.boson.BosonType.CHAR;
import static com.fillta.higgs.boson.BosonType.DOUBLE;
import static com.fillta.higgs.boson.BosonType.FLOAT;
import static com.fillta.higgs.boson.BosonType.INT;
import static com.fillta.higgs.boson.BosonType.LIST;
import static com.fillta.higgs.boson.BosonType.LONG;
import static com.fillta.higgs.boson.BosonType.MAP;
import static com.fillta.higgs.boson.BosonType.NULL;
import static com.fillta.higgs.boson.BosonType.POLO;
import static com.fillta.higgs.boson.BosonType.REFERENCE;
import static com.fillta.higgs.boson.BosonType.REQUEST_CALLBACK;
import static com.fillta.higgs.boson.BosonType.REQUEST_METHOD_NAME;
import static com.fillta.higgs.boson.BosonType.REQUEST_PARAMETERS;
import static com.fillta.higgs.boson.BosonType.RESPONSE_METHOD_NAME;
import static com.fillta.higgs.boson.BosonType.RESPONSE_PARAMETERS;
import static com.fillta.higgs.boson.BosonType.SHORT;
import static com.fillta.higgs.boson.BosonType.STRING;

/**
 * @author Courtney Robinson <courtney@crlog.info>
 */
public class BosonWriter {
    final HashMap<Object, Integer> references = new HashMap<>();
    final HashMap<Object, Integer> referencesWritten = new HashMap<>();
    final AtomicInteger reference = new AtomicInteger();
    final BosonMessage msg;
    private Logger log = LoggerFactory.getLogger(getClass());
    /**
     * The maximum number of times methods can invoked themselves.
     */
    public static final int MAX_RECURSION_DEPTH = 10;
    private final ReflectionUtil reflection = new ReflectionUtil(MAX_RECURSION_DEPTH);
    public static final Charset utf8 = Charset.forName("utf-8");

    public BosonWriter(BosonMessage msg) {
        this.msg = msg;
    }

    public ByteBuf serialize() {
        ByteBuf buffer = Unpooled.buffer();
        //first thing to write is the protocol version
        buffer.writeByte(msg.protocolVersion);
        //pad the buffer with 4 bytes which will be updated after serialization to set the size of the message
        buffer.writeInt(0);
        //then write the message itself
        if (msg.callback != null && !msg.callback.isEmpty()) {
            //otherwise its a request
            serializeRequest(buffer);
        } else {
            //if there's no callback then its a response...responses don't send callbacks
            serializeResponse(buffer);
        }
        //calculate the total size of the message. we wrote 5 bytes to the buffer before serializing
        //this means byte 6 until buffer.writerIndex() = total message size
        //set message size at index = 1 with value writerIndex() - 5 bytes
        buffer.setInt(1, buffer.writerIndex() - 5);
        return buffer;
    }

    private void serializeResponse(ByteBuf buffer) {
        //write the method name
        buffer.writeByte(RESPONSE_METHOD_NAME); //write type/flag - 1 byte
        writeString(buffer, msg.method);
        //write the parameters
        buffer.writeByte(RESPONSE_PARAMETERS); //write type/flag - int = 4 bytes
        validateAndWriteType(buffer, msg.arguments); //write the size/length and payload
        buffer.resetReaderIndex();
    }

    private void serializeRequest(ByteBuf buffer) {
        buffer.writeByte(REQUEST_METHOD_NAME); //write type/flag - 1 byte
        //write the method name
        writeString(buffer, msg.method);
        //write the callback name
        buffer.writeByte(REQUEST_CALLBACK); //write type/flag - 1 byte
        writeString(buffer, msg.callback);
        //write the parameters
        buffer.writeByte(REQUEST_PARAMETERS); //write type/flag - int = 4 bytes
        validateAndWriteType(buffer, msg.arguments); //write the size/length and payload
    }

    public void writeByte(ByteBuf buffer, byte b) {
        buffer.writeByte(BYTE);
        buffer.writeByte(b);
    }

    public void writeNull(ByteBuf buffer) {
        buffer.writeByte(NULL);
    }

    public void writeShort(ByteBuf buffer, short s) {
        buffer.writeByte(SHORT);
        buffer.writeShort(s);
    }

    public void writeInt(ByteBuf buffer, int i) {
        buffer.writeByte(INT);
        buffer.writeInt(i);
    }

    public void writeLong(ByteBuf buffer, long l) {
        buffer.writeByte(LONG);
        buffer.writeLong(l);
    }

    public void writeFloat(ByteBuf buffer, float f) {
        buffer.writeByte(FLOAT);
        buffer.writeFloat(f);
    }

    public void writeDouble(ByteBuf buffer, double d) {
        buffer.writeByte(DOUBLE);
        buffer.writeDouble(d);
    }

    public void writeBoolean(ByteBuf buffer, boolean b) {
        buffer.writeByte(BOOLEAN);
        if (b) {
            buffer.writeByte(1);
        } else {
            buffer.writeByte(0);
        }
    }

    public void writeChar(ByteBuf buffer, char c) {
        buffer.writeByte(CHAR);
        buffer.writeChar(c);
    }

    public void writeString(ByteBuf buffer, String s) {
        buffer.writeByte(STRING); //type
        byte[] str = s.getBytes(utf8);
        buffer.writeInt(str.length); //size
        buffer.writeBytes(str); //payload
    }

    public void writeList(ByteBuf buffer, List<Object> value) {
        buffer.writeByte(LIST); //type
        buffer.writeInt(value.size()); //size
        for (Object param : value) {
            if (param == null) {
                writeNull(buffer);
            } else {
                validateAndWriteType(buffer, param); //payload
            }
        }
    }

    /**
     * Write an array of any supported boson type to the given buffer.
     * If the buffer contains any unsupported type, this will fail by throwing an UnsupportedBosonTypeException
     *
     * @param value the value to write
     */
    public void writeArray(ByteBuf buffer, Object[] value) {
        buffer.writeByte(ARRAY); //type
        //we write the component type of the array or null if its not an array
        int component = getArrayComponent(value.getClass());
        buffer.writeByte(component);
        buffer.writeInt(value.length); //size
        for (Object param : value) {
            validateAndWriteType(buffer, param); //payload
        }
    }

    public void writeMap(ByteBuf buffer, Map<?, ?> value) {
        buffer.writeByte(MAP); //type
        buffer.writeInt(value.size()); //size
        for (Object key : value.keySet()) {
            Object v = value.get(key);
            validateAndWriteType(buffer, key); //key payload
            validateAndWriteType(buffer, v); //value payload
        }
    }

    /**
     * Serialize any* Java object.
     * Circular reference support based on
     * http://beza1e1.tuxen.de/articles/deepcopy.html
     * http://stackoverflow.com/questions/5157764/java-detect-circular-references-during-custom-cloning
     *
     * @param obj the object to write
     * @return true on success
     */
    public boolean writePolo(ByteBuf buffer, Object obj) {
        if (obj == null) {
            validateAndWriteType(buffer, obj);
            return false;
        }
        Class<BosonProperty> propertyClass = BosonProperty.class;
        Class<?> klass = obj.getClass();
        Map<String, Object> data = new HashMap<>();
        boolean ignoreInheritedFields = false;
        if (klass.isAnnotationPresent(propertyClass)) {
            ignoreInheritedFields = klass.getAnnotation(propertyClass).ignoreInheritedFields();
        }
        //get ALL (public,private,protect,package) fields declared in the class - includes inherited fields
        List<Field> fields = reflection.getAllFields(new ArrayList<Field>(), klass, 0);
        for (Field field : fields) {
            //if inherited fields are to be ignored then fields must be declared in the current class
            if (ignoreInheritedFields && klass != field.getDeclaringClass()) {
                continue;
            }
            field.setAccessible(true);
            boolean add = true;
            field.setAccessible(true);
            String name = field.getName();
            //add if annotated with BosonProperty
            if (field.isAnnotationPresent(propertyClass)) {
                BosonProperty ann = field.getAnnotation(propertyClass);
                if (ann != null && !ann.value().isEmpty()) {
                    name = ann.value();
                }
                if ((ann != null) && ann.ignore()) {
                    add = false;
                }
                //if configured to ignore inherited fields then
                //only fields declared in the object's class are allowed
                if ((ann != null) && ann.ignoreInheritedFields() && field.getDeclaringClass() != klass) {
                    add = false;
                }
            }
            if (add) {
                try {
                    data.put(name, field.get(obj));
                } catch (IllegalAccessException e) {
                    log.warn(String.format("Unable to access field %s in class %s", field.getName(),
                            field.getDeclaringClass().getName()), e);
                }
            }
        }
        //if at least one field is allowed to be serialized
        if (data.size() > 0) {
            buffer.writeByte(POLO); //type
            Integer ref = references.get(obj);
            if (ref != null) {
                writeReference(buffer, ref);
            } else {
                writeReference(buffer, -1);
            }
            writeString(buffer, klass.getName()); //class name
            buffer.writeInt(data.size()); //size
            for (String key : data.keySet()) {
                Object value = data.get(key);
                writeString(buffer, key); //key payload must be a string
                validateAndWriteType(buffer, value); //value payload
            }
        }
        //if no fields found that can be serialized then the arguments array
        //length will be more than it should be.
        return data.size() > 0;
    }

    /**
     * The JVM would return the java keywords int, long etc for all primitive types
     * on an array using the rules outlined below.
     * This is of no use when serializing/de-serializing so this method converts
     * java primitive names to their boson data type equivalent.
     * The rest of this java doc is from Java's Class class
     * which details how it treats array of primitives.
     * <p/>
     * <p> If this class object represents a primitive type or void, then the
     * name returned is a {@code String} equal to the Java language
     * keyword corresponding to the primitive type or void.
     * <p/>
     * <p> If this class object represents a class of arrays, then the internal
     * form of the name consists of the name of the element type preceded by
     * one or more '{@code [}' characters representing the depth of the array
     * nesting.  The encoding of element type names is as follows:
     * <p/>
     * <blockquote><table summary="Element types and encodings">
     * <tr><th> Element Type <th> &nbsp;&nbsp;&nbsp; <th> Encoding
     * <tr><td> boolean      <td> &nbsp;&nbsp;&nbsp; <td align=center> Z
     * <tr><td> byte         <td> &nbsp;&nbsp;&nbsp; <td align=center> B
     * <tr><td> char         <td> &nbsp;&nbsp;&nbsp; <td align=center> C
     * <tr><td> class or interface
     * <td> &nbsp;&nbsp;&nbsp; <td align=center> L<i>classname</i>;
     * <tr><td> double       <td> &nbsp;&nbsp;&nbsp; <td align=center> D
     * <tr><td> float        <td> &nbsp;&nbsp;&nbsp; <td align=center> F
     * <tr><td> int          <td> &nbsp;&nbsp;&nbsp; <td align=center> I
     * <tr><td> long         <td> &nbsp;&nbsp;&nbsp; <td align=center> J
     * <tr><td> short        <td> &nbsp;&nbsp;&nbsp; <td align=center> S
     * </table></blockquote>
     * <p/>
     * <p> The class or interface name <i>classname</i> is the binary name of
     * the class specified above.
     * <p/>
     * <p> Examples:
     * <blockquote><pre>
     * String.class.getName()
     * returns "java.lang.String"
     * byte.class.getName()
     * returns "byte"
     * (new Object[3]).getClass().getName()
     * returns "[Ljava.lang.Object;"
     * (new int[3][4][5][6][7][8][9]).getClass().getName()
     * returns {@code "[[[[[[[ I "}
     * </pre></blockquote>
     *
     * @return the fully qualified class name of a java primitive or null if the class
     *         is not an array
     */
    public int getArrayComponent(Class<?> klass) {
        String name = klass.isArray() ? klass.getComponentType().getName() : null;
        if (name != null) {
            switch (name) {
                case "boolean":
                case "java.lang.Boolean":
                    return BOOLEAN;
                case "byte":
                case "java.lang.Byte":
                    return BYTE;
                case "char":
                case "java.lang.Character":
                    return CHAR;
                case "double":
                case "java.lang.Double":
                    return DOUBLE;
                case "float":
                case "java.lang.Float":
                    return FLOAT;
                case "int":
                case "java.lang.Integer":
                    return INT;
                case "long":
                case "java.lang.Long":
                    return LONG;
                case "short":
                case "java.lang.Short":
                    return SHORT;
                default:
                    return POLO;
            }
        }
        return POLO;
    }

    /**
     * @param buffer the buffer to write to
     * @param param  the param to write to the buffer
     */
    public void validateAndWriteType(ByteBuf buffer, Object param) {
        if (param == null) {
            writeNull(buffer);
        } else {
            if (param instanceof Byte || Byte.class.isAssignableFrom(param.getClass())) {
                writeByte(buffer, (Byte) param);
            } else if (param instanceof Short || Short.class.isAssignableFrom(param.getClass())) {
                writeShort(buffer, (Short) param);
            } else if (param instanceof Integer || Integer.class.isAssignableFrom(param.getClass())) {
                writeInt(buffer, (Integer) param);
            } else if (param instanceof Long || Long.class.isAssignableFrom(param.getClass())) {
                writeLong(buffer, (Long) param);
            } else if (param instanceof Float || Float.class.isAssignableFrom(param.getClass())) {
                writeFloat(buffer, (Float) param);
            } else if (param instanceof Double || Double.class.isAssignableFrom(param.getClass())) {
                writeDouble(buffer, (Double) param);
            } else if (param instanceof Boolean || Boolean.class.isAssignableFrom(param.getClass())) {
                writeBoolean(buffer, (Boolean) param);
            } else if (param instanceof Character || Character.class.isAssignableFrom(param.getClass())) {
                writeChar(buffer, (Character) param);
            } else if (param instanceof String || String.class.isAssignableFrom(param.getClass())) {
                writeString(buffer, (String) param);
            } else if (param instanceof List || List.class.isAssignableFrom(param.getClass())) {
                writeList(buffer, (List<Object>) param);
            } else if (param instanceof Map || Map.class.isAssignableFrom(param.getClass())) {
                writeMap(buffer, (Map<Object, Object>) param);
            } else if (param.getClass().isArray()) {
                //array values can be reference types but not the arrays themselves
                writeArray(buffer, (Object[]) param);
            } else {
                //all other types can create circular references so needs checking
                Integer ref = references.get(param);
                //if a reference doesn't exist for the object then add it
                if (ref == null) {
                    //first time seeing it give it a reference
                    ref = reference.getAndIncrement();
                    //put it before attempting to write so if this gets called again during this write, it exists
                    references.put(param, ref);
                    //add param to list of references and discover all its dependencies
                    traverseObjectGraph(param);
                    verifyReferenceAndWrite(buffer, param, ref);
                } else {
                    //if is not written then write it
                    verifyReferenceAndWrite(buffer, param, ref);
                }
            }
        }
    }

    private void verifyReferenceAndWrite(ByteBuf buffer, Object param, Integer ref) {
        Integer writtenRef = referencesWritten.get(param);
        //if is not written then write it
        if (writtenRef == null) {
            writtenRef = ref;
            referencesWritten.put(param, writtenRef);
            writeReferenceType(buffer, param);
        } else {
            //if already written then write a reference to the already written object
            writeReference(buffer, writtenRef);
        }
    }

    private void writeReference(ByteBuf buffer, Integer ref) {
        //if the object has been written already then write a negative reference
        buffer.writeByte(REFERENCE);
        buffer.writeInt(ref);
    }

    private void writeReferenceType(ByteBuf buffer, Object param) {
        Class<?> obj = param.getClass();
        //only if they wouldn't create a circular reference do we write them
        if (List.class.isAssignableFrom(obj)) {
            writeList(buffer, (List<Object>) param);
        } else {
            if (Map.class.isAssignableFrom(obj)) {
                writeMap(buffer, (Map) param);
            } else {
                if (!writePolo(buffer, param)) {
                    log.warn(String.format("%s is not a supported type, see BosonType for a list of supported types",
                            obj.getName()));
                }
            }
        }
    }

    public void traverseObjectGraph(Object obj) {
        if (obj != null) {
            if (obj.getClass().isArray()) {
                traversArrayReferences(obj);
            } else {
                traversObjectReferences(obj);
            }
        }
    }

    private void traversArrayReferences(Object obj) {
        int length = Array.getLength(obj);
        for (int i = 0; i < length; i++) {
            Object f = Array.get(obj, i);
            traverseObjectGraph(f);
        }
    }

    public void traversObjectReferences(Object obj) {
        //if a ref hasn't been stored already
        if (!references.containsKey(obj)) {
            references.put(obj, reference.getAndIncrement());
        }
        //discover object dependencies.
        List<Field> fields = reflection.getAllFields(new ArrayList<Field>(), obj.getClass(), 0);
        for (Field field : fields) {
            try {
                Object f = field.get(obj);
                if (f != null) {
                    //if it has this key, we've already added it and its sub-graph, continue
                    if (!references.containsKey(f)) {
                        traverseObjectGraph(f);
                    }
                }
            } catch (IllegalAccessException e) {
                //ignore, keep calm, carry on
            }
        }
    }
}
