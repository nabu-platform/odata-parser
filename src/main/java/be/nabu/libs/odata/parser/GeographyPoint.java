package be.nabu.libs.odata.parser;

import java.util.List;

/**
 * Example
 * 
 * {
            "type": "Point",
            "coordinates": [
                -118.408055555556,
                33.9425
            ],
            "crs": {
                "type": "name",
                "properties": {
                    "name": "EPSG:4326"
                }
            }
        }
 * 
 *
 * Note that the specification (https://www.rfc-editor.org/rfc/rfc7946#page-8) seems to indicate the crs bit is deprecated
 */
public interface GeographyPoint {
	public String getType();
	public List<Double> getCoordinates();
}
