// License: Apache 2.0. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.geojson;

import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.progress.NullProgressMonitor;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.io.AbstractReader;
import org.openstreetmap.josm.io.IllegalDataException;
import org.openstreetmap.josm.tools.Logging;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonValue;
import javax.json.stream.JsonParser;
import javax.json.stream.JsonParser.Event;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.openstreetmap.josm.tools.I18n.tr;

/**
 * Reader that reads GeoJSON files. See https://tools.ietf.org/html/rfc7946 for more information.
 */
public class GeoJsonReader extends AbstractReader {

    private static final String COORDINATES = "coordinates";
    private static final String FEATURES = "features";
    private static final String PROPERTIES = "properties";
    private static final String GEOMETRY = "geometry";
    private static final String TYPE = "type";
    private JsonParser parser;

    GeoJsonReader() {
        // Restricts visibility
    }

    private void setParser(final JsonParser parser) {
        this.parser = parser;
    }

    private void parse() {
        while (parser.hasNext()) {
            Event event = parser.next();
            if (event == Event.START_OBJECT) {
                parseRoot(parser.getObject());
            }
        }
        parser.close();
    }

    private void parseRoot(final JsonObject object) {
        switch (object.getString(TYPE)) {
            case "FeatureCollection":
                parseFeatureCollection(object.getJsonArray(FEATURES));
                break;
            case "Feature":
                parseFeature(object);
                break;
            case "GeometryCollection":
                parseGeometryCollection(null, object);
                break;
            default:
                parseGeometry(null, object);
        }
    }

    private void parseFeatureCollection(final JsonArray features) {
        for (JsonValue feature : features) {
            if (feature instanceof JsonObject) {
                JsonObject item = (JsonObject) feature;
                parseFeature(item);
            }
        }
    }

    private void parseFeature(final JsonObject feature) {
        JsonObject geometry = feature.getJsonObject(GEOMETRY);
        parseGeometry(feature, geometry);
    }

    private void parseGeometryCollection(final JsonObject feature, final JsonObject geometry) {
        JsonArray geometries = geometry.getJsonArray("geometries");
        for (JsonValue jsonValue : geometries) {
            parseGeometry(feature, jsonValue.asJsonObject());
        }
    }

    private void parseGeometry(final JsonObject feature, final JsonObject geometry) {
        switch (geometry.getString(TYPE)) {
            case "Point":
                parsePoint(feature, geometry.getJsonArray(COORDINATES));
                break;
            case "MultiPoint":
                parseMultiPoint(feature, geometry);
                break;
            case "LineString":
                parseLineString(feature, geometry.getJsonArray(COORDINATES));
                break;
            case "MultiLineString":
                parseMultiLineString(feature, geometry);
                break;
            case "Polygon":
                parsePolygon(feature, geometry.getJsonArray(COORDINATES));
                break;
            case "MultiPolygon":
                parseMultiPolygon(feature, geometry);
                break;
            case "GeometryCollection":
                parseGeometryCollection(feature, geometry);
                break;
            default:
                parseUnknown(geometry);
        }
    }

    private void parsePoint(final JsonObject feature, final JsonArray coordinates) {
        double lat = coordinates.getJsonNumber(1).doubleValue();
        double lon = coordinates.getJsonNumber(0).doubleValue();
        Node node = createNode(lat, lon);
        fillTagsFromFeature(feature, node);
    }

    private void parseMultiPoint(final JsonObject feature, final JsonObject geometry) {
        JsonArray coordinates = geometry.getJsonArray(COORDINATES);
        for (JsonValue coordinate : coordinates) {
            parsePoint(feature, coordinate.asJsonArray());
        }
    }

    private void parseLineString(final JsonObject feature, final JsonArray coordinates) {
        if (coordinates.isEmpty()) {
            return;
        }
        createWay(coordinates, false)
            .ifPresent(way -> fillTagsFromFeature(feature, way));
    }

    private void parseMultiLineString(final JsonObject feature, final JsonObject geometry) {
        JsonArray coordinates = geometry.getJsonArray(COORDINATES);
        for (JsonValue coordinate : coordinates) {
            parseLineString(feature, coordinate.asJsonArray());
        }
    }

    private void parsePolygon(final JsonObject feature, final JsonArray coordinates) {
        if (coordinates.size() == 1) {
            createWay(coordinates.getJsonArray(0), true)
                .ifPresent(way -> fillTagsFromFeature(feature, way));
        } else if (coordinates.size() > 1) {
            // create multipolygon
            final Relation multipolygon = new Relation();
            multipolygon.put(TYPE, "multipolygon");
            createWay(coordinates.getJsonArray(0), true)
                .ifPresent(way -> multipolygon.addMember(new RelationMember("outer", way)));

            for (JsonValue interiorRing : coordinates.subList(1, coordinates.size())) {
                createWay(interiorRing.asJsonArray(), true)
                    .ifPresent(way -> multipolygon.addMember(new RelationMember("inner", way)));
            }

            fillTagsFromFeature(feature, multipolygon);
            getDataSet().addPrimitive(multipolygon);
        }
    }

    private void parseMultiPolygon(final JsonObject feature, final JsonObject geometry) {
        JsonArray coordinates = geometry.getJsonArray(COORDINATES);
        for (JsonValue coordinate : coordinates) {
            parsePolygon(feature, coordinate.asJsonArray());
        }
    }

    private Node createNode(final double lat, final double lon) {
        final Node node = new Node(new LatLon(lat, lon));
        getDataSet().addPrimitive(node);
        return node;
    }

    private Optional<Way> createWay(final JsonArray coordinates, final boolean autoClose) {
        if (coordinates.isEmpty()) {
            return Optional.empty();
        }
        final Way way = new Way();

        final List<Node> nodes = new ArrayList<>(coordinates.size());
        for (JsonValue coordinate : coordinates) {
            final JsonArray jsonValues = coordinate.asJsonArray();
            nodes.add(
                createNode(
                    jsonValues.getJsonNumber(1).doubleValue(),
                    jsonValues.getJsonNumber(0).doubleValue()
                )
            );
        }

        final int size = nodes.size();
        if (size > 1) {
            if (nodes.get(0).equals(nodes.get(size - 1))) {
                // Re-use first node to close the Polygon or LineString properly
                nodes.remove(size - 1);
                nodes.add(nodes.get(0));
            } else if (autoClose) {
                // Close Polygon, even if first and last node are not identical
                nodes.add(nodes.get(0));
            }
        }

        way.setNodes(nodes);

        getDataSet().addPrimitive(way);

        return Optional.of(way);
    }

    private void fillTagsFromFeature(final JsonObject feature, final OsmPrimitive primitive) {
        if (feature != null) {
            primitive.setKeys(getTags(feature));
        }
    }

    private void parseUnknown(final JsonObject object) {
        Logging.warn(tr("Unknown json object found {0}", object));
    }

    private Map<String, String> getTags(final JsonObject feature) {
        final Map<String, String> tags = new TreeMap<>();

        if (!feature.isNull(PROPERTIES)) {
            JsonObject properties = feature.getJsonObject(PROPERTIES);
            for (Map.Entry<String, JsonValue> stringJsonValueEntry : properties.entrySet()) {
                tags.put(stringJsonValueEntry.getKey(), String.valueOf(stringJsonValueEntry.getValue()));
            }
        }
        return tags;
    }

    @Override
    protected DataSet doParseDataSet(InputStream source, ProgressMonitor progressMonitor) throws IllegalDataException {
        setParser(Json.createParser(source));
        parse();

        return getDataSet();
    }

    /**
     * Parse the given input source and return the dataset.
     *
     * @param source          the source input stream. Must not be null.
     * @param progressMonitor the progress monitor. If null, {@link NullProgressMonitor#INSTANCE} is assumed
     * @return the dataset with the parsed data
     * @throws IllegalDataException     if an error was found while parsing the data from the source
     * @throws IllegalArgumentException if source is null
     */
    public static DataSet parseDataSet(InputStream source, ProgressMonitor progressMonitor) throws IllegalDataException {
        return new GeoJsonReader().doParseDataSet(source, progressMonitor);
    }

}
