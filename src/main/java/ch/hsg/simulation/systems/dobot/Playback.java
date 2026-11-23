package ch.hsg.simulation.systems.dobot;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * DobotStudio playback file ({@code .playback}), read like {@code parse_xml_to_commands} in the
 * lab's {@code dobot.py}: every {@code row…} element except {@code row_StudioVersion} with
 * {@code item_2} … {@code item_5} is one move to x, y, z, r. Other columns (motion type, pause,
 * suction) are ignored, so a playback never picks anything up. A row with a non-numeric
 * coordinate is skipped, as the lab code logs the error and carries on.
 */
public final class Playback {

    private Playback() {
    }

    /** @throws IllegalArgumentException if the text is not well-formed XML */
    public static List<Pose> parse(String xml) {
        Document document = read(xml);
        List<Pose> moves = new ArrayList<>();
        for (Node node = document.getDocumentElement().getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element row && row.getTagName().startsWith("row") && !row.getTagName().equals("row_StudioVersion")) {
                move(row).ifPresent(moves::add);
            }
        }
        return moves;
    }

    private static Optional<Pose> move(Element row) {
        Double[] coordinates = new Double[4];
        boolean[] present = new boolean[4];
        for (Node node = row.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element item && item.getTagName().startsWith("item_")) {
                int index;
                try {
                    index = Integer.parseInt(item.getTagName().substring("item_".length()));
                } catch (NumberFormatException exception) {
                    continue;
                }
                if (index >= 2 && index <= 5) {
                    present[index - 2] = true;
                    coordinates[index - 2] = number(item.getTextContent());
                }
            }
        }
        for (int i = 0; i < 4; i++) {
            if (!present[i] || coordinates[i] == null) {
                return Optional.empty();
            }
        }
        return Optional.of(new Pose(coordinates[0], coordinates[1], coordinates[2], coordinates[3]));
    }

    /** The lab code's test: one optional {@code .} and one optional {@code -}, otherwise digits. */
    private static Double number(String text) {
        String digits = text.replaceFirst("\\.", "").replaceFirst("-", "");
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Document read(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (SAXException | IOException exception) {
            throw new IllegalArgumentException("Invalid playback XML: " + exception.getMessage(), exception);
        } catch (ParserConfigurationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
