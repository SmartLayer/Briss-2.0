// $Id: MergedPanel.java 71 2012-05-26 16:21:23Z laborg $
/**
 * Copyright 2010, 2011 Gerhard Aigner, Rastislav Wartiak
 * <p>
 * This file is part of BRISS.
 * <p>
 * BRISS is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 * <p>
 * BRISS is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with
 * BRISS. If not, see http://www.gnu.org/licenses/.
 */

package at.laborg.briss.gui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

import at.laborg.briss.BrissGUI;
import at.laborg.briss.model.CropFinder;
import at.laborg.briss.model.PageCluster;
import at.laborg.briss.model.SplitFinder;

public class MergedPanel extends JPanel {

    private static final long serialVersionUID = -7279998240425762265L;
    // last drawn rectangle. a "ghosting" rectangle will
    // help the user to create the two equally sized crop rectangles
    private static DrawableCropRect curCrop;
    private static Point lastDragPoint;
    private static Point cropStartPoint;
    private static Point popUpMenuPoint;
    private static Point relativeHotCornerGrabDistance;
    private static ActionState actionState = ActionState.NOTHING;

    private final static int SELECT_BORDER_WIDTH = 1;
    private final static Font BASE_FONT = new Font(null, Font.PLAIN, 10);
    private final static Composite SMOOTH_NORMAL = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, .2f);
    private final static Composite SMOOTH_SELECT = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, .5f);
    private final static Composite XOR_COMPOSITE = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, .8f);
    private final static float[] DASH_PATTERN = {25f, 25f};
    private final static BasicStroke SELECTED_STROKE = new BasicStroke(SELECT_BORDER_WIDTH, BasicStroke.CAP_SQUARE,
        BasicStroke.JOIN_BEVEL, 1.0f, DASH_PATTERN, 0f);

    private final PageCluster cluster;

    private final List<DrawableCropRect> crops = new ArrayList<>();
    private final BufferedImage img;

    private enum ActionState {
        NOTHING, DRAWING_NEW_CROP, RESIZING_HOTCORNER_UL, RESIZING_HOTCORNER_LR, MOVE_CROP,
        RESIZING_HOTCORNER_UR, RESIZING_HOTCORNER_LL, RESIZING_RIGHT_EDGE, RESIZING_LEFT_EDGE,
        RESIZING_UPPER_EDGE, RESIZING_LOWER_EDGE
    }

    private static final Map<BiPredicate<DrawableCropRect, Point>, Integer> CURSORS_FROM_CROP_AND_POINT = new HashMap<>();

    static {
        CURSORS_FROM_CROP_AND_POINT.put(DrawableCropRect::isOverLeftEdge, Cursor.W_RESIZE_CURSOR);
        CURSORS_FROM_CROP_AND_POINT.put(DrawableCropRect::isOverRightEdge, Cursor.E_RESIZE_CURSOR);
        CURSORS_FROM_CROP_AND_POINT.put(DrawableCropRect::isOverUpperEdge, Cursor.N_RESIZE_CURSOR);
        CURSORS_FROM_CROP_AND_POINT.put(DrawableCropRect::isOverLowerEdge, Cursor.S_RESIZE_CURSOR);
        CURSORS_FROM_CROP_AND_POINT.put(DrawableCropRect::containsInHotCornerLL, Cursor.SW_RESIZE_CURSOR);
        CURSORS_FROM_CROP_AND_POINT.put(DrawableCropRect::containsInHotCornerLR, Cursor.SE_RESIZE_CURSOR);
        CURSORS_FROM_CROP_AND_POINT.put(DrawableCropRect::containsInHotCornerUL, Cursor.NW_RESIZE_CURSOR);
        CURSORS_FROM_CROP_AND_POINT.put(DrawableCropRect::containsInHotCornerUR, Cursor.NE_RESIZE_CURSOR);
        CURSORS_FROM_CROP_AND_POINT.put(DrawableCropRect::contains, Cursor.HAND_CURSOR);
    }

    private final BrissGUI briss;

    public MergedPanel(PageCluster cluster, BrissGUI briss) {
        super();
        this.briss = briss;
        this.cluster = cluster;
        this.img = cluster.getImageData().getPreviewImage();
        Float[] autoRatios = CropFinder.getAutoCropFloats(img);
        cluster.addRatios(autoRatios);
        setPreferredSize(new Dimension(img.getWidth(), img.getHeight()));
        setSize(new Dimension(img.getWidth(), img.getHeight()));
        if (cluster.getImageData().isRenderable()) {
            MergedPanelMouseAdapter mouseAdapter = new MergedPanelMouseAdapter();
            addMouseMotionListener(mouseAdapter);
            addMouseListener(mouseAdapter);
        }
        addRatiosAsCrops(cluster.getRatiosList());
        setToolTipText(createInfoString(cluster));
        addKeyListener(new MergedPanelKeyAdapter());
        setFocusable(true);
        repaint();
    }

    private void addRatiosAsCrops(List<Float[]> ratiosList) {
        for (Float[] ratios : cluster.getRatiosList()) {
            DrawableCropRect rect = new DrawableCropRect();
            rect.x = (int) (img.getWidth() * ratios[0]);
            rect.y = (int) (img.getHeight() * ratios[3]);
            rect.width = (int) (img.getWidth() * (1 - (ratios[0] + ratios[2])));
            rect.height = (int) (img.getHeight() * (1 - (ratios[1] + ratios[3])));
            crops.add(rect);
        }
    }

    private String createInfoString(PageCluster cluster) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html>");
        sb.append(cluster.isEvenPage() ? "Even " : "Odd ").append("page<br>");
        sb.append(cluster.getAllPages().size()).append(" pages: ");
        int pagecounter = 0;
        for (Integer pageNumber : cluster.getAllPages()) {
            sb.append(pageNumber).append(" ");
            if (pagecounter++ > 10) {
                pagecounter = 0;
                sb.append("<br>");
            }
        }
        sb.append("</html>");
        return sb.toString();
    }

    @Override
    public void paint(Graphics g) {
        update(g);
    }

    @Override
    public void update(Graphics g) {
        if (!isEnabled())
            return;

        Graphics2D g2 = (Graphics2D) g;
        g2.drawImage(img, null, 0, 0);

        // draw previously created rectangles
        int cropCnt = 0;

        for (DrawableCropRect crop : crops) {
            drawNormalCropRectangle(g2, cropCnt, crop);
            if (crop.isSelected()) {
                drawSelectionOverlay(g2, crop);
            }
            cropCnt++;
        }

        g2.dispose();

    }

    private void drawNormalCropRectangle(Graphics2D g2, int cropCnt, DrawableCropRect crop) {
        g2.setComposite(SMOOTH_NORMAL);
        g2.setColor(Color.BLUE);
        g2.fill(crop);
        g2.setColor(Color.BLACK);
        g2.setFont(scaleFont(String.valueOf(cropCnt + 1), crop));
        g2.drawString(String.valueOf(cropCnt + 1), crop.x, crop.y + crop.height);
        int cD = DrawableCropRect.CORNER_DIMENSION;
        g2.fillRect(crop.x, crop.y, cD, cD);
        g2.fillRect(crop.x + crop.width - cD - 1, crop.y, cD, cD);
        g2.fillRect(crop.x, crop.y + crop.height - cD - 1, cD, cD);
        g2.fillRect(crop.x + crop.width - cD - 1, crop.y + crop.height - cD - 1, cD, cD);
    }

    private void drawSelectionOverlay(Graphics2D g2, DrawableCropRect crop) {
        g2.setComposite(XOR_COMPOSITE);
        g2.setColor(Color.BLACK);

        g2.setStroke(SELECTED_STROKE);
        g2.drawRect(crop.x + SELECT_BORDER_WIDTH / 2, crop.y + SELECT_BORDER_WIDTH / 2, crop.width - SELECT_BORDER_WIDTH,
            crop.height - SELECT_BORDER_WIDTH);

        // display crop size in milimeters
        int w = Math.round(25.4f * crop.width / 72f);
        int h = Math.round(25.4f * crop.height / 72f);
        String size = w + "x" + h;
        g2.setFont(scaleFont(size, crop));
        g2.setColor(Color.YELLOW);
        g2.setComposite(SMOOTH_SELECT);
        g2.drawString(size, crop.x + SELECT_BORDER_WIDTH, crop.y + crop.height - SELECT_BORDER_WIDTH);
    }

    private void changeSelectRectangle(Point p) {
        for (DrawableCropRect crop : crops) {
            if (crop.contains(p)) {
                crop.setSelected(!crop.isSelected());
                break;
            }
        }
        repaint();
    }

    public int getWidestSelectedRect() {
        int max = -1;
        for (DrawableCropRect crop : crops) {
            if (crop.isSelected()) {
                if (crop.width > max) {
                    max = crop.width;
                }
            }
        }
        return max;
    }

    public int getHeighestSelectedRect() {
        int max = -1;
        for (DrawableCropRect crop : crops) {
            if (crop.isSelected()) {
                if (crop.height > max) {
                    max = crop.height;
                }
            }
        }
        return max;
    }

    public int getLeftmostSelectedRect() {
        int min = Integer.MAX_VALUE;
        for (DrawableCropRect crop : crops) {
            if (crop.isSelected()) {
                if (crop.x < min) {
                    min = crop.x;
                }
            }
        }
        return min;
    }

    public int getUpmostSelectedRect() {
        int min = Integer.MAX_VALUE;
        for (DrawableCropRect crop : crops) {
            if (crop.isSelected()) {
                if (crop.y < min) {
                    min = crop.y;
                }
            }
        }
        return min;
    }

    public Dimension getLargestRect() {
        int maxW = -1;
        int maxH = -1;
        for (DrawableCropRect crop : crops) {
            if (crop.width > maxW) {
                maxW = crop.width;
            }
            if (crop.height > maxH) {
                maxH = crop.height;
            }
        }
        return new Dimension(maxW, maxH);
    }

    public void setSelCropWidth(int width) {
        for (DrawableCropRect crop : crops) {
            if (crop.isSelected()) {
                int diffToMax = width - crop.width;
                crop.grow(diffToMax / 2, 0);
            }
        }
        updateClusterRatios(crops);
        repaint();
    }

    public void setSelCropHeight(int height) {
        for (DrawableCropRect crop : crops) {
            if (crop.isSelected()) {
                int diffToMax = height - crop.height;
                crop.grow(0, diffToMax / 2);
            }
        }
        updateClusterRatios(crops);
        repaint();
    }

    public void setSelCropSize(int width, int height) {
        for (DrawableCropRect crop : crops) {
            if (crop.isSelected()) {
                int diffToMaxW = width - crop.width;
                int diffToMaxH = height - crop.height;
                crop.grow(diffToMaxW / 2, diffToMaxH / 2);
            }
        }
        updateClusterRatios(crops);
        repaint();
    }

    public void resizeSelCrop(int width, int height) {
        for (DrawableCropRect crop : crops) {
            if (crop.isSelected()) {
                if (((width < 0) && (crop.width <= -width)) || ((height < 0) && (crop.height <= -height)))
                    return;
                crop.setSize(crop.width + width, crop.height + height);
            }
        }
        updateClusterRatios(crops);
        repaint();
    }

    public void setAllCropSize(int width, int height) {
        for (DrawableCropRect crop : crops) {
            crop.setSize(width, height);
        }
        updateClusterRatios(crops);
        repaint();
    }

    public void moveSelelectedCrops(int x, int y) {
        for (DrawableCropRect crop : crops) {
            if (crop.isSelected()) {
                int newX = crop.x + x;
                int newY = crop.y + y;
                crop.setLocation(newX, newY);
            }
        }
        repaint();
    }

    public void moveToSelelectedCrops(int x, int y) {
        for (DrawableCropRect crop : crops) {
            if (crop.isSelected()) {
                crop.setLocation(x, y);
            }
        }
        repaint();
    }

    public void selectCrops(boolean select) {
        for (DrawableCropRect crop : crops)
            crop.setSelected(select);
        repaint();
    }

    private void updateClusterRatios(List<DrawableCropRect> tmpCrops) {
        cluster.clearRatios();
        for (Rectangle crop : tmpCrops) {
            cluster.addRatios(getCutRatiosForPdf(crop, img.getWidth(), img.getHeight()));
        }
    }

    /**
     * creates the crop ratios from the user selection. 0 = left 1 = bottom 2 =
     * right 3 = top
     *
     * @param crop
     * @return the cropped ratios or null if to small
     */
    private static Float[] getCutRatiosForPdf(Rectangle crop, int imgWidth, int imgHeight) {
        int x1, x2, y1, y2;

        x1 = crop.x;
        x2 = x1 + crop.width;
        y1 = crop.y;
        y2 = y1 + crop.height;

        // check for maximum and minimum
        if (x1 < 0) {
            x1 = 0;
        }
        if (x2 > imgWidth) {
            x2 = imgWidth;
        }
        if (y1 < 0) {
            y1 = 0;
        }
        if (y2 > imgHeight) {
            y2 = imgHeight;
        }

        Float[] ratios = new Float[4];
        // left
        ratios[0] = (float) x1 / imgWidth;
        // bottom
        ratios[1] = (float) (imgHeight - y2) / imgHeight;
        // right
        ratios[2] = 1 - ((float) x2 / imgWidth);
        // top
        ratios[3] = 1 - ((float) (imgHeight - y1) / imgHeight);

        return ratios;
    }

    private Font scaleFont(String text, Rectangle rect) {

        int size = BASE_FONT.getSize();
        float width = this.getFontMetrics(BASE_FONT).stringWidth(text);
        float height = this.getFontMetrics(BASE_FONT).getHeight();
        if (width == 0 || height == 0)
            return BASE_FONT;
        float scaleFactorWidth = rect.width / width;
        float scaleFactorHeight = rect.height / height;
        float scaledWidth = (scaleFactorWidth * size);
        float scaledHeight = (scaleFactorHeight * size);
        return BASE_FONT.deriveFont((scaleFactorHeight > scaleFactorWidth) ? scaledWidth : scaledHeight);
    }

    private void splitIntoColumns() {
        ArrayList<DrawableCropRect> cropsCopy = new ArrayList<>(crops.size());

        for (DrawableCropRect crop : crops) {
            if (crop.isSelected()) {
                cropsCopy.addAll(SplitFinder.splitColumn(img, crop));
            } else {
                cropsCopy.add(crop);
            }
        }
        crops.clear();
        crops.addAll(cropsCopy);
        updateClusterRatios(crops);
        repaint();
    }

    private void splitIntoRows() {
        ArrayList<DrawableCropRect> cropsCopy = new ArrayList<>(crops.size());

        for (DrawableCropRect crop : crops) {
            if (crop.isSelected()) {
                cropsCopy.addAll(SplitFinder.splitRow(img, crop));
            } else {
                cropsCopy.add(crop);
            }
        }
        crops.clear();
        crops.addAll(cropsCopy);
        updateClusterRatios(crops);
        repaint();
    }

    private void copyToClipBoard() {
        ClipBoard.getInstance().clear();
        for (DrawableCropRect crop : crops) {
            if (crop.isSelected()) {
                ClipBoard.getInstance().addCrop(crop);
            }
        }
        updateClusterRatios(crops);
        repaint();
    }

    private void pasteFromClipBoard() {
        for (DrawableCropRect crop : ClipBoard.getInstance().getCrops()) {
            if (!crops.contains(crop)) {
                DrawableCropRect newCrop = new DrawableCropRect(crop);
                crops.add(newCrop);
            }
        }
        ClipBoard.getInstance().clear();
        updateClusterRatios(crops);
        repaint();
    }

    private void alignSelected(Point p) {
        for (DrawableCropRect crop : crops) {
            if (crop.contains(p)) {
                briss.alignSelRects(crop.x, crop.y, crop.width, crop.height);
                break;
            }
        }
    }

    private void deleteAllSelected() {
        List<DrawableCropRect> removeList = new ArrayList<>();
        for (DrawableCropRect crop : crops) {
            if (crop.isSelected()) {
                removeList.add(crop);
            }
        }
        crops.removeAll(removeList);
        updateClusterRatios(crops);
        repaint();
    }

    private void clipCropsToVisibleArea() {
        // clip to visible area
        for (Rectangle crop : crops) {
            if (crop.x < 0) {
                crop.width -= -crop.x;
                crop.x = 0;
            }
            if (crop.y < 0) {
                crop.height -= -crop.y;
                crop.y = 0;
            }
            if (crop.x + crop.width > getWidth()) {
                crop.width = getWidth() - crop.x;
            }
            if (crop.y + crop.height > getHeight()) {
                crop.height = getHeight() - crop.y;
            }
        }
    }

    private void removeTooSmallCrops() {
        // throw away all crops which are too small
        List<DrawableCropRect> cropsToTrash = new ArrayList<>();
        for (DrawableCropRect crop : crops) {
            if (crop.getWidth() < 2 * DrawableCropRect.CORNER_DIMENSION
                || crop.getHeight() < 2 * DrawableCropRect.CORNER_DIMENSION) {
                cropsToTrash.add(crop);
            }
        }
        crops.removeAll(cropsToTrash);
    }

    private class MergedPanelKeyAdapter extends KeyAdapter {

        @Override
        public void keyPressed(KeyEvent e) {
            switch (e.getKeyCode()) {
                case KeyEvent.VK_C:
                    if (e.getModifiersEx() == Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()) {
                        copyToClipBoard();
                    }
                    break;
                case KeyEvent.VK_V:
                    if (e.getModifiersEx() == Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()) {
                        pasteFromClipBoard();
                    }
                    break;
                case KeyEvent.VK_DELETE:
                    deleteAllSelected();
                    break;
                case KeyEvent.VK_LEFT:
                case KeyEvent.VK_RIGHT:
                case KeyEvent.VK_UP:
                case KeyEvent.VK_DOWN:
                    int x = 0;
                    int y = 0;
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_LEFT:
                            x = -1;
                            break;
                        case KeyEvent.VK_RIGHT:
                            x = 1;
                            break;
                        case KeyEvent.VK_UP:
                            y = -1;
                            break;
                        case KeyEvent.VK_DOWN:
                            y = 1;
                            break;
                    }
                    if ((e.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0) {
                        x *= 10;
                        y *= 10;
                    }
                    if ((e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0) {
                        briss.resizeSelRects(x, y);
                    } else {
                        briss.moveSelectedRects(x, y);
                    }
                    break;
                case KeyEvent.VK_ESCAPE:
                    briss.deselectAllRects();
                default:
            }
        }

    }

    private class MergedPanelMouseAdapter extends MouseAdapter implements ActionListener {

        @Override
        public void mouseMoved(MouseEvent e) {
            if (MergedPanel.this.contains(e.getPoint())) {
                MergedPanel.this.requestFocusInWindow();
            }

            Point p = e.getPoint();

            for (DrawableCropRect crop : crops) {
                for (Map.Entry<BiPredicate<DrawableCropRect, Point>, Integer> entry : CURSORS_FROM_CROP_AND_POINT.entrySet()) {
                    if (entry.getKey().test(crop, p)) {
                        setCursor(Cursor.getPredefinedCursor(entry.getValue()));

                        return;
                    }
                }
            }

            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (PopUpMenuForCropRectangles.DELETE.equals(e.getActionCommand())) {
                for (DrawableCropRect crop : crops) {
                    if (crop.contains(popUpMenuPoint)) {
                        crops.remove(crop);
                        break;
                    }
                }
                cluster.clearRatios();
                repaint();
            } else if (PopUpMenuForCropRectangles.SELECT_DESELECT.equals(e.getActionCommand())) {
                changeSelectRectangle(popUpMenuPoint);
            } else if (PopUpMenuForCropRectangles.SPLIT_INTO_COLUMNS.equals(e.getActionCommand())) {
                splitIntoColumns();
            } else if (PopUpMenuForCropRectangles.SPLIT_INTO_ROWS.equals(e.getActionCommand())) {
                splitIntoRows();
            } else if (PopUpMenuForCropRectangles.COPY.equals(e.getActionCommand())) {
                copyToClipBoard();
            } else if (PopUpMenuForCropRectangles.PASTE.equals(e.getActionCommand())) {
                pasteFromClipBoard();
            } else if (PopUpMenuForCropRectangles.ALIGN_SELECTED.equals(e.getActionCommand())) {
                alignSelected(popUpMenuPoint);
            }
        }

        @Override
        public void mouseDragged(MouseEvent mE) {
            Point curPoint = mE.getPoint();

            switch (actionState) {
                case DRAWING_NEW_CROP:
                    if (cropStartPoint == null) {
                        cropStartPoint = curPoint;
                    }
                    curCrop.x = Math.min(curPoint.x, cropStartPoint.x);
                    curCrop.width = Math.abs(curPoint.x - cropStartPoint.x);
                    curCrop.y = Math.min(curPoint.y, cropStartPoint.y);
                    curCrop.height = Math.abs(curPoint.y - cropStartPoint.y);
                    break;
                case MOVE_CROP:
                    if (lastDragPoint == null) {
                        lastDragPoint = curPoint;
                    }
                    if (mE.isShiftDown()) {
                        briss.moveSelectedRects(curPoint.x - lastDragPoint.x, curPoint.y - lastDragPoint.y);
                    } else {
                        curCrop.translate(curPoint.x - lastDragPoint.x, curPoint.y - lastDragPoint.y);
                    }
                    lastDragPoint = curPoint;
                    break;
                case RESIZING_HOTCORNER_LR:
                    if (lastDragPoint == null) {
                        lastDragPoint = curPoint;
                    }
                    if (mE.isShiftDown()) {
                        briss.resizeSelRects(curPoint.x - lastDragPoint.x, curPoint.y - lastDragPoint.y);
                    } else {
                        curPoint.translate(relativeHotCornerGrabDistance.x, relativeHotCornerGrabDistance.y);
                        curCrop.setNewHotCornerLR(curPoint);
                    }
                    lastDragPoint = curPoint;
                    break;
                case RESIZING_HOTCORNER_UL:
                    if (lastDragPoint == null) {
                        lastDragPoint = curPoint;
                    }
                    if (mE.isShiftDown()) {
                        briss.resizeSelRects(lastDragPoint.x - curPoint.x, lastDragPoint.y - curPoint.y);
                        briss.moveSelectedRects(curPoint.x - lastDragPoint.x, curPoint.y - lastDragPoint.y);
                    } else {
                        curPoint.translate(relativeHotCornerGrabDistance.x, relativeHotCornerGrabDistance.y);
                        curCrop.setNewHotCornerUL(curPoint);
                    }
                    lastDragPoint = curPoint;
                    break;
                case RESIZING_HOTCORNER_UR:
                    if (lastDragPoint == null) {
                        lastDragPoint = curPoint;
                    }
                    if (mE.isShiftDown()) {
                        briss.resizeSelRects(curPoint.x - lastDragPoint.x, lastDragPoint.y - curPoint.y);
                        briss.moveSelectedRects(0, curPoint.y - lastDragPoint.y);
                    } else {
                        curPoint.translate(relativeHotCornerGrabDistance.x, relativeHotCornerGrabDistance.y);
                        curCrop.setNewHotCornerUR(curPoint);
                    }
                    lastDragPoint = curPoint;
                    break;
                case RESIZING_HOTCORNER_LL:
                    if (lastDragPoint == null) {
                        lastDragPoint = curPoint;
                    }
                    if (mE.isShiftDown()) {
                        briss.resizeSelRects(lastDragPoint.x - curPoint.x, curPoint.y - lastDragPoint.y);
                        briss.moveSelectedRects(curPoint.x - lastDragPoint.x, 0);
                    } else {
                        curPoint.translate(relativeHotCornerGrabDistance.x, relativeHotCornerGrabDistance.y);
                        curCrop.setNewHotCornerLL(curPoint);
                    }
                    lastDragPoint = curPoint;
                    break;
                case RESIZING_LEFT_EDGE:
                    if (lastDragPoint == null) {
                        lastDragPoint = curPoint;
                    }

                    if (mE.isShiftDown()) {
                        briss.resizeSelRects(lastDragPoint.x - curPoint.x, 0);
                        briss.moveSelectedRects(curPoint.x - lastDragPoint.x, 0);
                    } else {
                        curCrop.moveLeftEdge(curPoint);
                    }
                    lastDragPoint = curPoint;
                    break;

                case RESIZING_RIGHT_EDGE:
                    if (lastDragPoint == null) {
                        lastDragPoint = curPoint;
                    }

                    if (mE.isShiftDown()) {
                        briss.resizeSelRects(curPoint.x - lastDragPoint.x, 0);
                    } else {
                        curCrop.moveRightEdge(curPoint);
                    }
                    lastDragPoint = curPoint;
                    break;

                case RESIZING_UPPER_EDGE:
                    if (lastDragPoint == null) {
                        lastDragPoint = curPoint;
                    }

                    if (mE.isShiftDown()) {
                        briss.resizeSelRects(0, lastDragPoint.y - curPoint.y);
                        briss.moveSelectedRects(0, curPoint.y - lastDragPoint.y);
                    } else {
                        curCrop.moveUpperEdge(curPoint);
                    }
                    lastDragPoint = curPoint;
                    break;

                case RESIZING_LOWER_EDGE:
                    if (lastDragPoint == null) {
                        lastDragPoint = curPoint;
                    }

                    if (mE.isShiftDown()) {
                        briss.resizeSelRects(0, curPoint.y - lastDragPoint.y);
                    } else {
                        curCrop.moveLowerEdge(curPoint);
                    }
                    lastDragPoint = curPoint;
                    break;
            default:
                break;
            }
            repaint();
        }

        @Override
        public void mousePressed(MouseEvent mE) {

            Point p = mE.getPoint();
            if (mE.isPopupTrigger()) {
                showPopUpMenu(mE);
            }

            if (mE.isControlDown()) {
                changeSelectRectangle(p);
                return;
            }

            if (SwingUtilities.isLeftMouseButton(mE)) {

                for (DrawableCropRect crop : crops) {
                    if (processUpperLeftHotCorner(p, crop)) {
                        return;
                    }

                    if (processLowerRightHotCorner(p, crop)) {
                        return;
                    }

                    if (processUpperRightHotCorner(p, crop)) {
                        return;
                    }

                    if (processLowerLeftHotCorner(p, crop)) {
                        return;
                    }

                    if (processRightEdge(p, crop)) {
                        return;
                    }

                    if (processLeftEdge(p, crop)) {
                        return;
                    }

                    if (processUpperEdge(p, crop)) {
                        return;
                    }

                    if (processLowerEdge(p, crop)) {
                        return;
                    }

                    if (processMoveCrop(p, crop)) {
                        return;
                    }
                }

                setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));

                // otherwise draw a new one
                actionState = ActionState.DRAWING_NEW_CROP;
                if (curCrop == null) {
                    curCrop = new DrawableCropRect();
                    crops.add(curCrop);
                    cropStartPoint = p;
                }
            }
        }

        private boolean processRightEdge(Point p, DrawableCropRect crop) {
            if (crop.isOverRightEdge(p)) {
                actionState = ActionState.RESIZING_RIGHT_EDGE;
                curCrop = crop;
                return true;
            }

            return false;
        }

        private boolean processLeftEdge(Point p, DrawableCropRect crop) {
            if (crop.isOverLeftEdge(p)) {
                actionState = ActionState.RESIZING_LEFT_EDGE;
                curCrop = crop;
                return true;
            }

            return false;
        }

        private boolean processUpperEdge(Point p, DrawableCropRect crop) {
            if (crop.isOverUpperEdge(p)) {
                actionState = ActionState.RESIZING_UPPER_EDGE;
                curCrop = crop;
                return true;
            }

            return false;
        }

        private boolean processLowerEdge(Point p, DrawableCropRect crop) {
            if (crop.isOverLowerEdge(p)) {
                actionState = ActionState.RESIZING_LOWER_EDGE;
                curCrop = crop;
                return true;
            }

            return false;
        }

        private boolean processMoveCrop(Point p, DrawableCropRect crop) {
            if (crop.contains(p)) {
                actionState = ActionState.MOVE_CROP;
                curCrop = crop;
                return true;
            }

            return false;
        }

        private boolean processLowerLeftHotCorner(Point p, DrawableCropRect crop) {
            if (crop.containsInHotCornerLL(p)) {
                actionState = ActionState.RESIZING_HOTCORNER_LL;
                relativeHotCornerGrabDistance = new Point(crop.x - p.x, crop.y + crop.height - p.y);
                curCrop = crop;
                return true;
            }

            return false;
        }

        private boolean processUpperRightHotCorner(Point p, DrawableCropRect crop) {
            if (crop.containsInHotCornerUR(p)) {
                actionState = ActionState.RESIZING_HOTCORNER_UR;
                relativeHotCornerGrabDistance = new Point(crop.x + crop.width - p.x, crop.y - p.y);
                curCrop = crop;
                return true;
            }

            return false;
        }

        private boolean processLowerRightHotCorner(Point p, DrawableCropRect crop) {
            if (crop.containsInHotCornerLR(p)) {
                actionState = ActionState.RESIZING_HOTCORNER_LR;
                relativeHotCornerGrabDistance = new Point(crop.x + crop.width - p.x, crop.y + crop.height - p.y);
                curCrop = crop;
                return true;
            }

            return false;
        }

        private boolean processUpperLeftHotCorner(Point p, DrawableCropRect crop) {
            if (crop.containsInHotCornerUL(p)) {
                actionState = ActionState.RESIZING_HOTCORNER_UL;
                relativeHotCornerGrabDistance = new Point(crop.x - p.x, crop.y - p.y);
                curCrop = crop;
                return true;
            }

            return false;
        }

        @Override
        public void mouseReleased(MouseEvent mE) {
            if (mE.isPopupTrigger()) {
                showPopUpMenu(mE);
            }
            clipCropsToVisibleArea();
            removeTooSmallCrops();
            updateClusterRatios(crops);
            actionState = ActionState.NOTHING;
            cropStartPoint = null;
            lastDragPoint = null;
            curCrop = null;
            repaint();
        }

        private void showPopUpMenu(MouseEvent e) {
            popUpMenuPoint = e.getPoint();
            new PopUpMenuForCropRectangles().show(e.getComponent(), e.getX(), e.getY());
        }

        private class PopUpMenuForCropRectangles extends JPopupMenu {
            public static final String DELETE = "Delete rectangle";
            public static final String SELECT_DESELECT = "Select/Deselect rectangle";
            public static final String SPLIT_INTO_COLUMNS = "Split into columns";
            public static final String SPLIT_INTO_ROWS = "Split into rows";
            public static final String COPY = "Copy Selected rectangles";
            public static final String PASTE = "Paste rectangles";
            public static final String ALIGN_SELECTED = "Align selected rectangles";

            public PopUpMenuForCropRectangles() {

                boolean isContainedInRectangle = false;
                for (DrawableCropRect crop : crops) {
                    if (crop.contains(popUpMenuPoint)) {
                        isContainedInRectangle = true;
                    }
                }
                if (isContainedInRectangle) {
                    JMenuItem deleteItem = new JMenuItem(DELETE);
                    deleteItem.addActionListener(MergedPanelMouseAdapter.this);
                    add(deleteItem);
                    JMenuItem selectDeselectItem = new JMenuItem(SELECT_DESELECT);
                    selectDeselectItem.addActionListener(MergedPanelMouseAdapter.this);
                    add(selectDeselectItem);
                }
                boolean cropRectIsSelected = false;
                for (DrawableCropRect crop : crops) {
                    if (crop.isSelected()) {
                        cropRectIsSelected = true;
                    }
                }
                JMenuItem splitColumns = new JMenuItem(SPLIT_INTO_COLUMNS);
                splitColumns.addActionListener(MergedPanelMouseAdapter.this);
                splitColumns.setEnabled(cropRectIsSelected);
                add(splitColumns);
                JMenuItem splitRows = new JMenuItem(SPLIT_INTO_ROWS);
                splitRows.addActionListener(MergedPanelMouseAdapter.this);
                splitRows.setEnabled(cropRectIsSelected);
                add(splitRows);
                JMenuItem copyItem = new JMenuItem(COPY);
                copyItem.addActionListener(MergedPanelMouseAdapter.this);
                copyItem.setEnabled(cropRectIsSelected);
                add(copyItem);
                JMenuItem pasteItem = new JMenuItem(PASTE);
                pasteItem.addActionListener(MergedPanelMouseAdapter.this);
                pasteItem.setEnabled(ClipBoard.getInstance().getAmountOfCropsInClipBoard() > 0);
                add(pasteItem);

                JMenuItem alignItem = new JMenuItem(ALIGN_SELECTED);
                alignItem.addActionListener(MergedPanelMouseAdapter.this);
                alignItem.setEnabled(true);
                add(alignItem);
            }
        }
    }
}
