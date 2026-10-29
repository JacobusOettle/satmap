package com.jacobusoettle.satmap.map

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jacobusoettle.satmap.core.GeoPoint
import com.jacobusoettle.satmap.core.KMLColor
import com.jacobusoettle.satmap.core.KMLDocument
import com.jacobusoettle.satmap.core.KMLFeature
import com.jacobusoettle.satmap.core.KMLGeometry
import com.jacobusoettle.satmap.core.KMLStyle
import com.jacobusoettle.satmap.core.PolygonLabel
import com.jacobusoettle.satmap.kml.KMLLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.util.UUID

/** A KML file's shapes and labels on the map (iOS `BlockLayers.shapes` and its renderers' styles): every style case,
 *  the width limits, the app's white when the file's colours are off, and the label points. */
@RunWith(AndroidJUnit4::class)
class BlockLayersTest {
    private val id = UUID.fromString("6F1C2D3E-4B5A-4C7D-8E9F-0A1B2C3D4E5F")

    /** A small block (an open ring, as KML may leave it) and a road. */
    private val ring = listOf(GeoPoint(-33.92, 18.92), GeoPoint(-33.92, 18.93), GeoPoint(-33.93, 18.93))
    private val road = listOf(GeoPoint(-33.90, 18.90), GeoPoint(-33.91, 18.91))
    private val red = KMLColor(1.0, 0.0, 0.0, 1.0)
    private val halfGreen = KMLColor(0.0, 0.5, 0.0, 0.5)

    private fun file(vararg features: KMLFeature) =
        KMLLibrary.File(id, "Farm", "farm.kml", KMLDocument("Farm", features.toList()), isShown = true)

    private fun block(style: KMLStyle?, name: String? = null) =
        KMLFeature(name, KMLGeometry.Polygon(ring, emptyList()), style)

    private fun line(style: KMLStyle?) = KMLFeature(null, KMLGeometry.Line(road), style)

    private fun only(f: KMLFeature, colours: Boolean = true): Feature = BlockLayers.shapes(file(f), colours).features.single()

    @Test
    fun withoutAStyleBlocksAreWhiteWithAFaintFill() {
        val p = only(block(null))
        assertEquals("rgba(255,255,255,0.07)", p.getStringProperty("fill"))
        assertEquals("rgba(255,255,255,0.95)", p.getStringProperty("stroke"))
        assertEquals(2.5, p.getNumberProperty("width").toDouble(), 0.0)
        val l = only(line(null))
        assertEquals("rgba(255,255,255,0.95)", l.getStringProperty("stroke"))
        assertEquals(2.5, l.getNumberProperty("width").toDouble(), 0.0)
        assertFalse(l.hasProperty("fill"))
    }

    @Test
    fun aStyleGivesItsColoursAndWidth() {
        val style = KMLStyle(lineColor = red, lineWidth = 4.0, fillColor = halfGreen)
        val p = only(block(style))
        assertEquals("rgba(0,128,0,0.5)", p.getStringProperty("fill"))
        assertEquals("rgba(255,0,0,1.0)", p.getStringProperty("stroke"))
        assertEquals(4.0, p.getNumberProperty("width").toDouble(), 0.0)
        val l = only(line(style))
        assertEquals("rgba(255,0,0,1.0)", l.getStringProperty("stroke"))
        assertEquals(4.0, l.getNumberProperty("width").toDouble(), 0.0)
    }

    @Test
    fun aStyleThatLeavesColoursUnsetKeepsTheAppsOwn() {
        val p = only(block(KMLStyle(lineWidth = 3.0)))
        assertEquals(BlockLayers.defaultFill, p.getStringProperty("fill"))
        assertEquals(BlockLayers.defaultStroke, p.getStringProperty("stroke"))
        assertEquals(3.0, p.getNumberProperty("width").toDouble(), 0.0)
    }

    @Test
    fun noFillAndNoOutline() {
        assertEquals(BlockLayers.clear, only(block(KMLStyle(fillColor = red, fill = false))).getStringProperty("fill"))
        val outline = only(block(KMLStyle(lineColor = red, outline = false)))
        assertEquals(BlockLayers.clear, outline.getStringProperty("stroke"))
        assertEquals(BlockLayers.defaultFill, outline.getStringProperty("fill"))
        // A line has no outline to switch off: it keeps its stroke.
        assertEquals("rgba(255,0,0,1.0)", only(line(KMLStyle(lineColor = red, outline = false))).getStringProperty("stroke"))
        assertEquals("rgba(0,0,0,0)", BlockLayers.clear)
    }

    @Test
    fun widthsAreKeptBetween1point5And12AndNoneMeansNoStroke() {
        for (w in listOf(0.0, -1.0)) {
            val p = only(block(KMLStyle(lineColor = red, lineWidth = w)))
            assertEquals(BlockLayers.clear, p.getStringProperty("stroke"))
            assertEquals(1.5, p.getNumberProperty("width").toDouble(), 0.0)
            assertEquals(BlockLayers.clear, only(line(KMLStyle(lineWidth = w))).getStringProperty("stroke"))
        }
        val thin = only(line(KMLStyle(lineWidth = 0.5)))
        assertEquals(BlockLayers.defaultStroke, thin.getStringProperty("stroke"))
        assertEquals(1.5, thin.getNumberProperty("width").toDouble(), 0.0)
        assertEquals(12.0, only(block(KMLStyle(lineWidth = 20.0))).getNumberProperty("width").toDouble(), 0.0)
        assertEquals(7.0, only(line(KMLStyle(lineWidth = 7.0))).getNumberProperty("width").toDouble(), 0.0)
    }

    @Test
    fun withTheFilesColoursOffEverythingIsWhite() {
        val style = KMLStyle(lineColor = red, lineWidth = 8.0, fillColor = halfGreen, fill = false, outline = false,
            iconColor = red)
        val p = only(block(style), colours = false)
        assertEquals(BlockLayers.defaultFill, p.getStringProperty("fill"))
        assertEquals(BlockLayers.defaultStroke, p.getStringProperty("stroke"))
        assertEquals(2.5, p.getNumberProperty("width").toDouble(), 0.0)
        assertEquals(BlockLayers.defaultStroke, only(line(style), colours = false).getStringProperty("stroke"))
        val point = KMLFeature("Launch 1", KMLGeometry.Point(GeoPoint(-33.9175, 18.924)), style)
        assertNull(BlockLayers.shapes(file(point), useFileColours = false).labels.single().colour)
        assertEquals(Color.RED, BlockLayers.shapes(file(point), useFileColours = true).labels.single().colour)
    }

    @Test
    fun polygonsComeBeforeLinesGroupedByStyleAsIOSAddsItsOverlays() {
        val a = KMLStyle(lineColor = red)
        val b = KMLStyle(lineColor = halfGreen)
        val shapes = BlockLayers.shapes(file(line(a), block(a), block(b), line(b), block(a)), true).features
        assertEquals(listOf("Polygon", "Polygon", "Polygon", "LineString", "LineString"), shapes.map { it.geometry()!!.type() })
        // Style a's blocks, then b's; a's line, then b's.
        assertEquals(
            listOf("rgba(255,0,0,1.0)", "rgba(255,0,0,1.0)", "rgba(0,128,0,0.5)", "rgba(255,0,0,1.0)", "rgba(0,128,0,0.5)"),
            shapes.map { it.getStringProperty("stroke") },
        )
    }

    @Test
    fun ringsAreClosedAndHolesKept() {
        val hole = listOf(GeoPoint(-33.925, 18.925), GeoPoint(-33.925, 18.926), GeoPoint(-33.926, 18.926), GeoPoint(-33.925, 18.925))
        val f = BlockLayers.shapes(file(KMLFeature(null, KMLGeometry.Polygon(ring, listOf(hole)))), true).features.single()
        val rings = (f.geometry() as Polygon).coordinates()
        assertEquals(2, rings.size)
        assertEquals(4, rings[0].size) // closed: the first point again
        assertEquals(rings[0].first(), rings[0].last())
        assertEquals(Point.fromLngLat(18.92, -33.92), rings[0][0])
        assertEquals(4, rings[1].size) // already closed
        val l = BlockLayers.shapes(file(line(null)), true).features.single().geometry() as LineString
        assertEquals(2, l.coordinates().size) // a line stays open
    }

    @Test
    fun namedBlocksAndPointsGetLabels() {
        val named = block(null, name = "A1 Shiraz")
        val unnamed = block(null)
        val launch = KMLFeature("Launch 1", KMLGeometry.Point(GeoPoint(-33.9175, 18.924)), KMLStyle(iconColor = halfGreen))
        val noName = KMLFeature(null, KMLGeometry.Point(GeoPoint(-33.918, 18.925)))
        val labels = BlockLayers.shapes(file(named, unnamed, launch, line(null), noName), true).labels
        assertEquals(3, labels.size)
        assertEquals(BlockLayers.Label(PolygonLabel.interiorPoint(ring, emptyList())!!, "A1 Shiraz", isPoint = false), labels[0])
        assertEquals(BlockLayers.Label(GeoPoint(-33.9175, 18.924), "Launch 1", isPoint = true, colour = Color.argb(128, 0, 128, 0)), labels[1])
        assertEquals(BlockLayers.Label(GeoPoint(-33.918, 18.925), "", isPoint = true), labels[2])
    }

    @Test
    fun aLabelsPointSaysWhichLayerImageAndPointItIs() {
        val point = BlockLayers.Label(GeoPoint(-33.9175, 18.924), "Launch 1", isPoint = true)
        val f = BlockLayers.labelFeature(id, point, MapImages.kmlLabelId(id, 2), 52f, 31f, 0)
        assertEquals("point", f.getStringProperty("kind"))
        assertEquals("kml/6f1c2d3e-4b5a-4c7d-8e9f-0a1b2c3d4e5f/2", f.getStringProperty("img"))
        assertEquals(0, f.getNumberProperty("i").toInt())
        assertEquals("Launch 1", f.getStringProperty("name"))
        assertEquals(id.toString(), f.getStringProperty("file"))
        assertEquals(52.0, f.getNumberProperty("w").toDouble(), 0.0)
        assertEquals(31.0, f.getNumberProperty("h").toDouble(), 0.0)
        assertEquals(Point.fromLngLat(18.924, -33.9175), f.geometry())
        val name = BlockLayers.labelFeature(id, point.copy(isPoint = false), "x", 10f, 17f, null)
        assertEquals("name", name.getStringProperty("kind"))
        assertFalse(name.hasProperty("i"))
    }

    @Test
    fun kmlColoursAsAndroidAndStyleColours() {
        assertEquals(Color.argb(128, 0, 128, 0), halfGreen.argb())
        assertEquals(Color.RED, red.argb())
        assertEquals("rgba(0,128,0,0.5)", halfGreen.rgba())
        assertEquals("rgba(255,0,0,1.0)", red.rgba())
        // As KML writes them: aabbggrr.
        assertEquals(0x80FF8000.toInt(), KMLColor("800080ff")!!.argb())
        assertTrue(KMLColor("ff0000ff")!!.rgba().startsWith("rgba(255,0,0,"))
    }

    @Test
    fun theKmlLayersHaveTheirOwnIds() {
        assertEquals("kml/$id", MapIds.kml(id))
        assertEquals("kml/$id/fill", MapIds.kmlFill(id))
        assertEquals("kml/$id/line", MapIds.kmlLine(id))
        assertEquals("kml/$id/labels", MapIds.kmlLabels(id))
        assertEquals("kml/$id/names", MapIds.kmlNames(id))
        assertEquals("kml/$id/points", MapIds.kmlPoints(id))
    }
}
