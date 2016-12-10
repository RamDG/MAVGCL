/****************************************************************************
 *
 *   Copyright (c) 2016 Eike Mansfeld ecm@gmx.de. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS
 * OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 ****************************************************************************/

package com.comino.flight.widgets.statusline;

import java.io.IOException;
import java.util.List;

import com.comino.flight.log.FileHandler;
import com.comino.flight.model.AnalysisDataModel;
import com.comino.flight.model.service.AnalysisModelService;
import com.comino.flight.observables.StateProperties;
import com.comino.flight.widgets.charts.control.ChartControlWidget;
import com.comino.flight.widgets.charts.control.IChartControl;
import com.comino.flight.widgets.fx.controls.Badge;
import com.comino.mav.control.IMAVController;
import com.comino.msp.main.control.listener.IMSPStatusChangedListener;
import com.comino.msp.model.segment.Status;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.FloatProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleFloatProperty;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;

public class StatusLineWidget extends Pane implements IChartControl, IMSPStatusChangedListener  {

	@FXML
	private Label version;

	@FXML
	private Badge driver;

	@FXML
	private Badge messages;

	@FXML
	private Badge time;

	@FXML
	private Badge mode;

	@FXML
	private Badge rc;

	@FXML
	private ProgressBar progress;

	private IMAVController control;

	private FloatProperty scroll       = new SimpleFloatProperty(0);

	private AnalysisModelService collector = AnalysisModelService.getInstance();
	private StateProperties state = null;

	private String filename;
	private long tms;

	private AnimationTimer task;

	public StatusLineWidget() {
		FXMLLoader fxmlLoader = new FXMLLoader(getClass().getResource("StatusLineWidget.fxml"));
		fxmlLoader.setRoot(this);
		fxmlLoader.setController(this);
		try {
			fxmlLoader.load();
		} catch (IOException exception) {

			throw new RuntimeException(exception);
		}

		task = new AnimationTimer() {
			List<AnalysisDataModel> list = null;

			@Override public void handle(long now) {
				if((System.currentTimeMillis()-tms)>500) {
					filename = FileHandler.getInstance().getName();

					if(control.isConnected() && !state.getLogLoadedProperty().get()) {
						messages.setMode(Badge.MODE_ON);
						driver.setText(control.getCurrentModel().sys.getSensorString());
						driver.setMode(Badge.MODE_ON);
					} else {
						messages.setMode(Badge.MODE_OFF);
						driver.setText("no sensor info available");
						driver.setMode(Badge.MODE_OFF);
					}

					if((System.currentTimeMillis() - tms)>30000) {
						messages.clear();
						tms = System.currentTimeMillis();
					}

					list = collector.getModelList();

					if(list.size()>0) {

						int current_x0_pt = collector.calculateX0Index(scroll.floatValue());
						int current_x1_pt = collector.calculateX1Index(scroll.floatValue());
						time.setText(
								String.format("TimeFrame: [ %1$tM:%1$tS - %2$tM:%2$tS ]",
										list.get(current_x0_pt).tms/1000,
										list.get(current_x1_pt).tms/1000)
								);
						time.setBackgroundColor(Color.DARKCYAN);
					} else {
						time.setText("TimeFrame: [ 00:00 - 00:00 ]");
						time.setBackgroundColor(Color.GRAY);
					}

					if(filename.isEmpty()) {
						if(control.isConnected()) {
							time.setMode(Badge.MODE_ON);
							if(control.isSimulation()) {
								mode.setBackgroundColor(Color.BEIGE);
								mode.setText("SITL");
							} else {
								mode.setBackgroundColor(Color.DARKCYAN);
								mode.setText("Connected");
							}
							mode.setMode(Badge.MODE_ON);
						} else {
							mode.setMode(Badge.MODE_OFF); mode.setText("offline");
							time.setMode(Badge.MODE_OFF);
						}
					} else {
						time.setMode(Badge.MODE_ON);
						messages.clear();
						mode.setBackgroundColor(Color.LIGHTSKYBLUE);
						mode.setText(filename);
						mode.setMode(Badge.MODE_ON);
					}

				}
			}
		};
        driver.setAlignment(Pos.CENTER_LEFT);
	}

	public void setup(ChartControlWidget chartControlWidget, IMAVController control) {
		chartControlWidget.addChart(this);
		this.control = control;
		this.control.addStatusChangeListener(this);
		this.state = StateProperties.getInstance();

		messages.setText(control.getClass().getSimpleName()+ " loaded");
		messages.setBackgroundColor(Color.GRAY);

		control.addMAVMessageListener(msg -> {
			Platform.runLater(() -> {
				if(filename.isEmpty()) {
					tms = System.currentTimeMillis();
					messages.setText(msg.msg);
				}
			});
		});

		progress.setVisible(false);

		StateProperties.getInstance().getProgressProperty().addListener((v,ov,nv) -> {
			if(nv.floatValue() > -1) {
				progress.setVisible(true);
				Platform.runLater(() -> {
					progress.setProgress(nv.floatValue());
				});
			} else {
				progress.setVisible(false);
			}
		});

		task.start();
	}

	@Override
	public void update(Status arg0, Status newStat) {

		Platform.runLater(() -> {

			if((newStat.isStatus(Status.MSP_RC_ATTACHED) || newStat.isStatus(Status.MSP_JOY_ATTACHED))
					&& newStat.isStatus(Status.MSP_CONNECTED))
				rc.setMode(Badge.MODE_ON);
			else
				rc.setMode(Badge.MODE_OFF);

		});
	}

	@Override
	public FloatProperty getScrollProperty() {
		return scroll;
	}

	@Override
	public IntegerProperty getTimeFrameProperty() {
		return null;
	}

	public BooleanProperty getIsScrollingProperty() {
		return null;
	}

	@Override
	public void refreshChart() {

	}

}
