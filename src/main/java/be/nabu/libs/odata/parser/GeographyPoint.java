/*
* Copyright (C) 2022 Alexander Verbruggen
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

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
